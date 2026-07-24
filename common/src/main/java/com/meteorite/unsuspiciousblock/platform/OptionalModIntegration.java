package com.meteorite.unsuspiciousblock.platform;

import java.lang.reflect.InvocationTargetException;

/**
 * 可选模组联动的类加载边界，避免常驻入口在依赖缺失时解析第三方 API 类型。
 */
public final class OptionalModIntegration {
    private OptionalModIntegration() {
    }

    // 仅应在平台确认对应模组已加载后调用。
    public static <T> T instantiate(String className, Class<T> contract) {
        try {
            Class<? extends T> implementation = Class.forName(className).asSubclass(contract);
            return implementation.getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            throw new IllegalStateException("Failed to load optional integration " + className, exception);
        }
    }

    // 调用可选联动类的无参静态工厂，并校验返回类型。
    public static <T> T invokeFactory(String className, String methodName, Class<T> contract) {
        try {
            Object result = Class.forName(className).getMethod(methodName).invoke(null);
            return contract.cast(result);
        } catch (ReflectiveOperationException | LinkageError | ClassCastException exception) {
            Throwable cause = exception instanceof InvocationTargetException invocation
                    ? invocation.getCause()
                    : exception;
            throw new IllegalStateException("Failed to load optional integration " + className, cause);
        }
    }
}
