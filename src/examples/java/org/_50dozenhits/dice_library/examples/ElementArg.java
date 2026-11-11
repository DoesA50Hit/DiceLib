package org._50dozenhits.dice_library.examples;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// From the DiceLib Guide, Command Framework. docs/build_guide.py copies this class back into that page.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface ElementArg {
    String value() default "element";
}
