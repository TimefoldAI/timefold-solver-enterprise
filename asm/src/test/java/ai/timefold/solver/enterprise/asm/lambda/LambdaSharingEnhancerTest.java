package ai.timefold.solver.enterprise.asm.lambda;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

public class LambdaSharingEnhancerTest {
    private static class SharedLambdas {
        Function<String, Integer> a1() {
            return String::length;
        }

        Function<String, Integer> a2() {
            return String::length;
        }

        Function<String, Integer> b1() {
            return s -> s.length() + 1;
        }

        Function<String, Integer> b2() {
            return s -> s.length() + 1;
        }

        BiFunction<String, String, Integer> biFunctionB() {
            return (s, s2) -> s.length() + 1;
        }

        Function<String, Integer> c() {
            return s -> s.length() + 2;
        }

        Function<String, Long> d() {
            return s -> s.length() + 1L;
        }

        Supplier<Integer> supplier(int value) {
            return () -> value;
        }

        Supplier<Boolean> predicate(String value) {
            return value::isEmpty;
        }
    }

    @Test
    void sharesLambdas() throws Throwable {
        MethodHandles.Lookup lookup = MethodHandles.lookup().defineHiddenClass(
                LambdaSharingEnhancer.shareLambdasInClass(SharedLambdas.class), true);
        Class<?> clazz = lookup.lookupClass();
        Object instance = lookup.findConstructor(clazz, MethodType.methodType(void.class)).invoke();
        assertThat(lookup.findVirtual(clazz, "a1", MethodType.methodType(Function.class)).invoke(instance))
                .isSameAs(lookup.findVirtual(clazz, "a2", MethodType.methodType(Function.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "b1", MethodType.methodType(Function.class)).invoke(instance))
                .isSameAs(lookup.findVirtual(clazz, "b2", MethodType.methodType(Function.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "a1", MethodType.methodType(Function.class)).invoke(instance))
                .isNotSameAs(lookup.findVirtual(clazz, "b1", MethodType.methodType(Function.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "b1", MethodType.methodType(Function.class)).invoke(instance))
                .isNotSameAs(
                        lookup.findVirtual(clazz, "biFunctionB", MethodType.methodType(BiFunction.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "b1", MethodType.methodType(Function.class)).invoke(instance))
                .isNotSameAs(lookup.findVirtual(clazz, "c", MethodType.methodType(Function.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "b1", MethodType.methodType(Function.class)).invoke(instance))
                .isNotSameAs(lookup.findVirtual(clazz, "d", MethodType.methodType(Function.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "c", MethodType.methodType(Function.class)).invoke(instance))
                .isNotSameAs(lookup.findVirtual(clazz, "d", MethodType.methodType(Function.class)).invoke(instance));
        assertThat(lookup.findVirtual(clazz, "supplier", MethodType.methodType(Supplier.class, int.class)).invoke(instance, 0))
                .isNotSameAs(lookup.findVirtual(clazz, "supplier", MethodType.methodType(Supplier.class, int.class))
                        .invoke(instance, 0));
        assertThat(lookup.findVirtual(clazz, "predicate", MethodType.methodType(Supplier.class, String.class)).invoke(instance,
                "a"))
                .isNotSameAs(lookup.findVirtual(clazz, "predicate", MethodType.methodType(Supplier.class, String.class))
                        .invoke(instance, "a"));
    }
}
