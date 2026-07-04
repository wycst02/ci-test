package io.github.wycst.wastnet.env;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import java.lang.invoke.*;
import java.lang.reflect.Field;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * @Date 2024/2/19 16:08
 * @Created by wangyc
 */
@SuppressWarnings("unchecked")
class RuntimeEnvJDK9Plus extends RuntimeEnv {

    static final MethodHandles.Lookup LOOKUP;
    static final Function<SSLEngine, String> APPLICATION_PROTOCOL_GET_FUNCTION;
    static final BiConsumer<SSLParameters, String[]> APPLICATIONPROTOCOLS_SET_CONSUMER;

    static {
        MethodHandles.Lookup lookup = null;
        try {
            Field field = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
            long fieldOffset = UNSAFE.staticFieldOffset(field);
            lookup = (MethodHandles.Lookup) UNSAFE.getObject(MethodHandles.Lookup.class, fieldOffset);
        } catch (Throwable throwable) {
        }
        LOOKUP = lookup == null ? MethodHandles.publicLookup() : lookup;
        MethodHandles.Lookup sslEngineCaller = LOOKUP.in(SSLEngine.class);
        try {
            MethodHandle handle = sslEngineCaller.unreflect(SSLEngine.class.getMethod("getApplicationProtocol"));
            MethodType methodType = handle.type();
            CallSite callSite = LambdaMetafactory.metafactory(
                    sslEngineCaller,
                    "apply",
                    MethodType.methodType(Function.class),
                    methodType.generic(),
                    handle,
                    methodType);
            APPLICATION_PROTOCOL_GET_FUNCTION = (Function<SSLEngine, String>) callSite.getTarget().invoke();
        } catch (Throwable throwable) {
            throw new UnsupportedOperationException(throwable);
        }

        MethodHandles.Lookup sslParametersCaller = LOOKUP.in(SSLParameters.class);
        try {
            MethodHandle methodHandle = sslParametersCaller.findVirtual(SSLParameters.class, "setApplicationProtocols", MethodType.methodType(void.class, String[].class));
            MethodType samMethodType = MethodType.methodType(void.class, Object.class, Object.class);
            MethodType instantiatedMethodType = MethodType.methodType(void.class, SSLParameters.class, String[].class);
            CallSite callSite = LambdaMetafactory.metafactory(
                    sslParametersCaller,
                    "accept",
                    MethodType.methodType(BiConsumer.class),
                    samMethodType,
                    methodHandle,
                    instantiatedMethodType);
            APPLICATIONPROTOCOLS_SET_CONSUMER = (BiConsumer<SSLParameters, String[]>) callSite.getTarget().invokeExact();
        } catch (Throwable throwable) {
            throw new UnsupportedOperationException(throwable);
        }
    }

    public RuntimeEnvJDK9Plus() {}

    @Override
    public void setApplicationProtocols(SSLEngine sslEngine, String[] applicationProtocols) {
        SSLParameters sslParameters = new SSLParameters();
        if (applicationProtocols != null && applicationProtocols.length > 0) {
            APPLICATIONPROTOCOLS_SET_CONSUMER.accept(sslParameters, applicationProtocols);
        }
        sslEngine.setSSLParameters(sslParameters);
    }

    @Override
    public String getSSLApplicationProtocol(SSLEngine sslEngine) {
        return sslEngine == null ? null : APPLICATION_PROTOCOL_GET_FUNCTION.apply(sslEngine);
    }
}
