// 只缓存当前 JEI runtime 的引用；每次导出重新创建独立布局。
if (typeof RecipeCards !== 'undefined') RecipeCards.closeStream();
var RecipeCards = {
    plugin: Java.loadClass('com.meteorite.tools.recipecards.client.RecipeCardJeiPlugin'),
    bridge: Java.loadClass('com.meteorite.tools.recipecards.client.RecipeCardExports'),
    objects: Java.loadClass('java.util.Objects'),
    session: null,
    closeStream: function() {
        if (this.session && this.session.stream) {
            this.session.stream.close();
            this.session.stream = null;
        }
    },
    requireSession: function(token) {
        if (!this.session || this.session.token !== token) throw new Error('配方清单会话已变化，请重新获取');
        if (!this.objects.equals(this.session.runtime, this.plugin.requireRuntime())) throw new Error('JEI 已重新加载，请重新获取配方清单');
        return this.session;
    },
    begin: function(token, filters) {
        if (this.bridge.isBusy()) throw new Error('已有配方卡片正在导出');
        this.closeStream();
        var runtime = this.plugin.requireRuntime();
        var stream = runtime.getRecipeManager().createRecipeCategoryLookup().get();
        var categories;
        try { categories = stream.toList(); } finally { stream.close(); }
        this.session = {token: token, runtime: runtime, categories: categories, categoryIndex: 0,
            sourceIndex: 0, entries: [], filters: filters, stream: null, iterator: null, state: 'scanning'};
        return this.summary(token);
    },
    summary: function(token) {
        var session = this.requireSession(token);
        return {token: token, state: session.state, total: session.entries.length, error: session.error || null};
    },
    step: function(token) {
        var session = this.requireSession(token);
        if (session.state !== 'scanning') return this.summary(token);
        try {
            // 分段读取，留出客户端主线程处理正常游戏 tick。
            var budget = 128;
            while (budget > 0 && session.categoryIndex < session.categories.size()) {
                var category = session.categories.get(session.categoryIndex);
                var categoryId = String(category.getRecipeType().getUid());
                if (session.filters.categories.length && session.filters.categories.indexOf(categoryId) < 0) {
                    session.categoryIndex++;
                    continue;
                }
                if (session.stream === null) {
                    session.stream = session.runtime.getRecipeManager().createRecipeLookup(category.getRecipeType()).get();
                    session.iterator = session.stream.iterator();
                    session.sourceIndex = 0;
                }
                if (!session.iterator.hasNext()) {
                    this.closeStream();
                    session.categoryIndex++;
                    continue;
                }
                var recipe = session.iterator.next(), sourceIndex = session.sourceIndex++;
                budget--;
                var registryName = category.getRegistryName(recipe);
                var recipeId = registryName === null ? null : String(registryName);
                var namespace = (recipeId || categoryId).split(':')[0];
                if (session.filters.namespaces.indexOf('*') < 0 && session.filters.namespaces.indexOf(namespace) < 0) continue;
                if (session.filters.recipes.length && session.filters.recipes.indexOf(recipeId) < 0) continue;
                session.entries.push({category: category, recipe: recipe, descriptor: {
                    category: categoryId, recipe: recipeId, sourceIndex: sourceIndex,
                    anonymous: recipeId === null, title: String(category.getTitle().getString())}});
            }
            if (session.categoryIndex === session.categories.size()) {
                session.entries.sort(function(a, b) {
                    var left = a.descriptor, right = b.descriptor;
                    var keyA = left.category + '/' + (left.recipe || ''), keyB = right.category + '/' + (right.recipe || '');
                    return keyA < keyB ? -1 : keyA > keyB ? 1 : left.sourceIndex - right.sourceIndex;
                });
                var occurrences = Object.create(null);
                session.entries.forEach(function(entry) {
                    var descriptor = entry.descriptor;
                    var key = descriptor.category + '/' + descriptor.recipe;
                    descriptor.occurrence = descriptor.anonymous ? null : (occurrences[key] || 0);
                    occurrences[key] = (occurrences[key] || 0) + 1;
                });
                session.state = 'ready';
            }
        } catch (error) {
            this.closeStream();
            session.state = 'failed';
            session.error = String(error);
        }
        return this.summary(token);
    },
    page: function(token, offset) {
        var session = this.requireSession(token);
        if (session.state !== 'ready') throw new Error('配方清单尚未就绪');
        return session.entries.slice(offset, offset + 64).map(function(entry, index) {
            var result = entry.descriptor;
            result.index = offset + index;
            return result;
        });
    },
    submit: function(token, index, options) {
        var session = this.requireSession(token);
        if (session.state !== 'ready' || !session.entries[index]) throw new Error('无效的配方索引');
        var entry = session.entries[index];
        return String(this.bridge.submit(entry.category, entry.recipe, JSON.stringify(options)));
    },
    status: function(id) { return JSON.parse(String(this.bridge.status(id))); }
};
'recipe-cards-installed';
