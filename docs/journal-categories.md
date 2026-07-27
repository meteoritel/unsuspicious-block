# 考古手册目录分类 Data Pack

分类文件位于 `data/<namespace>/journal_categories/<id>.json`。同一资源 ID 遵循正常的 Data Pack
优先级覆盖规则。分类首先执行 `special_rules`，未命中特例时再使用 `types`，最终未匹配的顶层表进入
`unsuspiciousblock:other`。

```json
{
  "translation_key": "example.category.name",
  "fallback": "Example",
  "description_key": "example.category.description",
  "description_fallback": "Example loot tables.",
  "icon": "minecraft:book",
  "order": 60,
  "priority": 0,
  "types": ["example:context"],
  "special_rules": [
    {
      "type": "minecraft:chest",
      "table_ids": ["example:chests/special"],
      "namespaces": ["example"],
      "path_prefixes": ["chests/"],
      "exclude_table_ids": [],
      "exclude_path_prefixes": [],
      "priority": 100
    }
  ]
}
```

- `order` 控制分类卡片顺序。
- 分类 `priority` 解决同一 `type` 映射到多个分类的冲突。
- 特例 `priority` 解决一张表同时命中多个特殊判定的冲突。
- `table_ids`、`namespaces` 和 `path_prefixes` 若同时存在，必须全部满足；各数组内部任一值匹配即可。
- `icon` 使用物品 ID。物品不存在时，客户端回退到书本图标。
- 名称和描述优先使用本地化 key，客户端缺少对应翻译时显示 fallback。
- 收录范围仍由战利品表追踪配置决定。直接命中配置规则的表属于显式追踪表，即使被其他表引用，也会作为根表显示并使用自身 JSON `type` 分类。
- 未直接命中配置、仅被父表引用的具名表属于隐式追踪表，不显示为根表，只能作为子表查看。
- 子表沿引用路径继承父表分类，不使用自身 JSON `type` 重新分类；同一张显式追踪表作为根表显示时仍使用自身分类。
