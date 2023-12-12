package ai.timefold.solver.enterprise.asm.lambda;

import static ai.timefold.solver.enterprise.asm.ASMConstants.ASM_VERSION;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

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
        super(ASM_VERSION, classVisitor);
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
