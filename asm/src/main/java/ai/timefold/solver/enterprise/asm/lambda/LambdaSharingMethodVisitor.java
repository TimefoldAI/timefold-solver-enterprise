package ai.timefold.solver.enterprise.asm.lambda;

import static ai.timefold.solver.enterprise.asm.ASMConstants.ASM_VERSION;

import java.lang.invoke.CallSite;
import java.lang.invoke.LambdaMetafactory;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Arrays;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.ToIntBiFunction;
import java.util.function.ToIntFunction;

import ai.timefold.solver.core.api.function.PentaFunction;
import ai.timefold.solver.core.api.function.PentaPredicate;
import ai.timefold.solver.core.api.function.QuadFunction;
import ai.timefold.solver.core.api.function.QuadPredicate;
import ai.timefold.solver.core.api.function.ToIntQuadFunction;
import ai.timefold.solver.core.api.function.ToIntTriFunction;
import ai.timefold.solver.core.api.function.TriFunction;
import ai.timefold.solver.core.api.function.TriPredicate;

import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.InstructionAdapter;

/**
 * This {@link MethodVisitor} creates temporarily invalid bytecode; It converts lambdas/method references to fields reads, but
 * does not create/initialize fields. The reason is we need to initialize the fields in clinit, but clinit itself need to be
 * visited, and we do not know the fields or their initializers until visitEnd of this visitor is called.
 */
final class LambdaSharingMethodVisitor extends InstructionAdapter {
    public record FunctionInterfaceId(String classDescriptor, Type methodGenericType) {
    }

    public record MethodReferenceId(FunctionInterfaceId functionInterfaceId, Handle methodHandle) {
    }

    public record LambdaId(FunctionInterfaceId functionInterfaceId, String methodId) {
    }

    public record InvokeDynamicArgs(String name, String descriptor, Handle bootstrapMethodHandle,
            Object[] bootstrapMethodArguments, String signature) {
        public Type getFieldDescriptor() {
            return Type.getReturnType(descriptor);
        }
    }

    final Map<String, String> methodIdToCanonicalMethodId;
    final Map<MethodReferenceId, String> methodReferenceIdToGeneratedFieldName;
    final Map<LambdaId, String> lambdaIdToGeneratedFieldName;
    final Map<String, InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs;
    final String classInternalName;

    public LambdaSharingMethodVisitor(MethodVisitor methodVisitor, String classInternalName,
            Map<String, String> methodIdToCanonicalMethodId,
            Map<MethodReferenceId, String> methodReferenceIdToGeneratedFieldName,
            Map<LambdaId, String> lambdaIdToGeneratedFieldName,
            Map<String, InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs) {
        super(ASM_VERSION, methodVisitor);
        this.classInternalName = classInternalName;
        this.methodIdToCanonicalMethodId = methodIdToCanonicalMethodId;
        this.methodReferenceIdToGeneratedFieldName = methodReferenceIdToGeneratedFieldName;
        this.lambdaIdToGeneratedFieldName = lambdaIdToGeneratedFieldName;
        this.generatedFieldNameToInvokeDynamicArgs = generatedFieldNameToInvokeDynamicArgs;
    }

    static String getMethodId(String name, String descriptor) {
        return name + " " + descriptor;
    }

    static String getDescriptor(String key) {
        return key.substring(key.indexOf(' ') + 1);
    }

    @Override
    public void invokedynamic(
            final String name,
            final String descriptor,
            final Handle bootstrapMethodHandle,
            final Object[] bootstrapMethodArguments) {
        if (Type.getMethodType(descriptor).getArgumentTypes().length != 0) {
            // The lambda depends on variables used in the method and thus cannot be transformed
            super.invokedynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            return;
        }
        if (!bootstrapMethodHandle.getOwner().equals(Type.getInternalName(LambdaMetafactory.class))) {
            super.invokedynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            return;
        }
        if (!bootstrapMethodHandle.getName().equals("metafactory")) {
            super.invokedynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            return;
        }
        if (!bootstrapMethodHandle.getDesc().equals(Type.getMethodDescriptor(
                Type.getType(CallSite.class),
                Type.getType(MethodHandles.Lookup.class),
                Type.getType(String.class),
                Type.getType(MethodType.class),
                Type.getType(MethodType.class),
                Type.getType(MethodHandle.class),
                Type.getType(MethodType.class)))) {
            super.invokedynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            return;
        }
        if (bootstrapMethodArguments.length != 3) {
            super.invokedynamic(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
            return;
        }
        Handle methodHandle = (Handle) bootstrapMethodArguments[1];
        Type methodGenericSignature = (Type) bootstrapMethodArguments[2];

        if (!methodHandle.getOwner().equals(classInternalName)) {
            replaceMethodReference(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments, methodHandle,
                    methodGenericSignature);
        } else {
            replaceLambda(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments, methodHandle,
                    methodGenericSignature);
        }
    }

    private void replaceMethodReference(final String name,
            final String descriptor,
            final Handle bootstrapMethodHandle,
            final Object[] bootstrapMethodArguments,
            Handle methodHandle,
            Type methodGenericSignature) {
        MethodReferenceId methodReferenceId =
                new MethodReferenceId(new FunctionInterfaceId(descriptor, methodGenericSignature), methodHandle);
        replaceDynamicWithFieldRead(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments,
                methodGenericSignature, methodReferenceIdToGeneratedFieldName, methodReferenceId);
    }

    private void replaceLambda(final String name,
            final String descriptor,
            final Handle bootstrapMethodHandle,
            final Object[] bootstrapMethodArguments,
            Handle methodHandle,
            Type methodGenericSignature) {
        String methodKey = getMethodId(methodHandle.getName(), methodHandle.getDesc());
        String canonicalMethodId = methodIdToCanonicalMethodId.get(methodKey);
        LambdaId lambdaId = new LambdaId(new FunctionInterfaceId(descriptor, methodGenericSignature), canonicalMethodId);
        replaceDynamicWithFieldRead(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments,
                methodGenericSignature, lambdaIdToGeneratedFieldName, lambdaId);
    }

    private <Key_> void replaceDynamicWithFieldRead(final String name,
            final String descriptor,
            final Handle bootstrapMethodHandle,
            final Object[] bootstrapMethodArguments,
            Type methodGenericSignature,
            Map<Key_, String> idToGeneratedField,
            Key_ id) {
        Type fieldType = Type.getReturnType(descriptor);
        if (idToGeneratedField.containsKey(id)) {
            String generatedFieldName = idToGeneratedField.get(id);
            this.getstatic(classInternalName, generatedFieldName, fieldType.getDescriptor());
            return;
        }
        int fieldId = generatedFieldNameToInvokeDynamicArgs.size();
        String generatedFieldName = "$timefoldSharedLambda" + fieldId;
        String signature = InterfaceSignature.getInterfaceSignature(Type.getReturnType(descriptor), methodGenericSignature);
        generatedFieldNameToInvokeDynamicArgs.put(generatedFieldName,
                new InvokeDynamicArgs(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments, signature));
        idToGeneratedField.put(id, generatedFieldName);
        this.getstatic(classInternalName, generatedFieldName, fieldType.getDescriptor());
    }

    private enum InterfaceSignature {
        // Predicates
        PREDICATE(Predicate.class, Type::getArgumentTypes),
        BI_PREDICATE(BiPredicate.class, Type::getArgumentTypes),
        TRI_PREDICATE(TriPredicate.class, Type::getArgumentTypes),
        QUAD_PREDICATE(QuadPredicate.class, Type::getArgumentTypes),
        PENTA_PREDICATE(PentaPredicate.class, Type::getArgumentTypes),

        // Functions
        FUNCTION(Function.class, sig -> append(sig.getArgumentTypes(), sig.getReturnType())),
        BI_FUNCTION(BiFunction.class, sig -> append(sig.getArgumentTypes(), sig.getReturnType())),
        TRI_FUNCTION(TriFunction.class, sig -> append(sig.getArgumentTypes(), sig.getReturnType())),
        QUAD_FUNCTION(QuadFunction.class, sig -> append(sig.getArgumentTypes(), sig.getReturnType())),
        PENTA_FUNCTION(PentaFunction.class, sig -> append(sig.getArgumentTypes(), sig.getReturnType())),

        // ToIntFunctions
        TO_INT_FUNCTION(ToIntFunction.class, Type::getArgumentTypes),
        TO_INT_BI_FUNCTION(ToIntBiFunction.class, Type::getArgumentTypes),
        TO_INT_TRI_FUNCTION(ToIntTriFunction.class, Type::getArgumentTypes),
        TO_INT_QUAD_FUNCTION(ToIntQuadFunction.class, Type::getArgumentTypes),

        // ToLongFunctions
        TO_LONG_FUNCTION(ToIntFunction.class, Type::getArgumentTypes),
        TO_LONG_BI_FUNCTION(ToIntBiFunction.class, Type::getArgumentTypes),
        TO_LONG_TRI_FUNCTION(ToIntTriFunction.class, Type::getArgumentTypes),
        TO_LONG_QUAD_FUNCTION(ToIntQuadFunction.class, Type::getArgumentTypes);

        final Class<?> interfaceClass;
        final Function<Type, Type[]> methodGenericSignatureToClassGenericArgs;

        InterfaceSignature(Class<?> interfaceClass,
                Function<Type, Type[]> methodGenericSignatureToClassGenericArgs) {
            this.interfaceClass = interfaceClass;
            this.methodGenericSignatureToClassGenericArgs = methodGenericSignatureToClassGenericArgs;
        }

        private static Type[] append(Type[] elements, Type appended) {
            Type[] out = Arrays.copyOf(elements, elements.length + 1);
            out[elements.length] = appended;
            return out;
        }

        public String getInterfaceSignature(Type methodGenericSignature) {
            Type[] genericArguments = methodGenericSignatureToClassGenericArgs.apply(methodGenericSignature);
            StringBuilder out = new StringBuilder();

            out.append("L");
            out.append(Type.getInternalName(interfaceClass));
            out.append("<");

            for (Type genericArgument : genericArguments) {
                out.append(genericArgument.getDescriptor());
            }

            out.append(">;");
            return out.toString();
        }

        public static String getInterfaceSignature(Type interfaceType, Type methodGenericSignature) {
            for (InterfaceSignature functionalInterface : values()) {
                if (Type.getType(functionalInterface.interfaceClass).equals(interfaceType)) {
                    return functionalInterface.getInterfaceSignature(methodGenericSignature);
                }
            }
            return null;
        }
    }
}