package ai.timefold.solver.enterprise.asm.lambda;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class LambdaSharingClassVisitor extends ClassVisitor {
    private final Map<String, String> methodIdToCanonicalMethodId;
    private final String classInternalName;
    private final Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs;
    private final Map<LambdaSharingMethodVisitor.MethodReferenceId, String> methodReferenceIdToGeneratedFieldName =
            new HashMap<>();
    private final Map<LambdaSharingMethodVisitor.LambdaId, String> lambdaIdToGeneratedFieldName = new HashMap<>();

    LambdaSharingClassVisitor(ClassVisitor classVisitor, String className,
            Map<String, String> methodIdToCanonicalMethodId,
            Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs) {
        super(Opcodes.ASM9, classVisitor);
        this.methodIdToCanonicalMethodId = methodIdToCanonicalMethodId;
        this.generatedFieldNameToInvokeDynamicArgs = generatedFieldNameToInvokeDynamicArgs;
        this.classInternalName = className.replace('.', '/');
    }

    @Override
    public MethodVisitor visitMethod(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final String[] exceptions) {
        return new LambdaSharingMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions),
                classInternalName, methodIdToCanonicalMethodId, methodReferenceIdToGeneratedFieldName,
                lambdaIdToGeneratedFieldName, generatedFieldNameToInvokeDynamicArgs);
    }
}
