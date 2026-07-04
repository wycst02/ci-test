package io.github.wycst.wastnet.http.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a field for dependency injection.
 * <p>
 * The field's type is used to look up a matching bean from the container.
 *
 * @author wangyc
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Inject {
}
