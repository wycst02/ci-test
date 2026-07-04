package io.github.wycst.wastnet.http.annotation;

import io.github.wycst.wastnet.http.HttpMethod;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Mark a method as an HTTP endpoint within a {@link Controller @Controller} class.
 *
 * @author wangyc
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Endpoint {
    String value();
    HttpMethod[] allowMethods() default {};
}
