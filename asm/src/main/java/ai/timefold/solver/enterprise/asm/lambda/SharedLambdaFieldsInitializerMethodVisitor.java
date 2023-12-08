package ai.timefold.solver.enterprise.asm.lambda;

import java.util.Map;

import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class SharedLambdaFieldsInitializerMethodVisitor extends MethodVisitor {
    private final String internalClassName;
    private final Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs;

    SharedLambdaFieldsInitializerMethodVisitor(int api, MethodVisitor methodVisitor,
            String internalClassName,
            Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs) {
        super(api, methodVisitor);
        this.internalClassName = internalClassName;
        this.generatedFieldNameToInvokeDynamicArgs = generatedFieldNameToInvokeDynamicArgs;
    }

    @Override
    public void visitCode() {
        for (Map.Entry<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameAndInitializerEntry : generatedFieldNameToInvokeDynamicArgs
                .entrySet()) {
            String generatedFieldName = generatedFieldNameAndInitializerEntry.getKey();
            LambdaSharingMethodVisitor.InvokeDynamicArgs invokeDynamicArgs = generatedFieldNameAndInitializerEntry.getValue();
            mv.visitInvokeDynamicInsn(invokeDynamicArgs.name(),
                    invokeDynamicArgs.descriptor(),
                    invokeDynamicArgs.bootstrapMethodHandle(),
                    invokeDynamicArgs.bootstrapMethodArguments());
            mv.visitFieldInsn(Opcodes.PUTSTATIC, internalClassName, generatedFieldName,
                    invokeDynamicArgs.getFieldDescriptor().getDescriptor());
        }
        super.visitCode();
    }

    @Override
    public void visitMaxs(int maxStack, int maxLocals) {
        // In case there a static block that only calls a static void method taking no parameters
        super.visitMaxs(Math.max(1, maxStack), maxLocals);
    }
}