package android.annotation;

/** 最小 SuppressLint 替身（仅注解，无运行时行为）。 */
public @interface SuppressLint {
    String[] value();
}
