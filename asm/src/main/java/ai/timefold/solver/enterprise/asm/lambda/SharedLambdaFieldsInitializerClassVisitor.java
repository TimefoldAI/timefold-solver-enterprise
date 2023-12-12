package ai.timefold.solver.enterprise.asm.lambda;

import static ai.timefold.solver.enterprise.asm.ASMConstants.ASM_VERSION;

import java.lang.reflect.Modifier;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

final class SharedLambdaFieldsInitializerClassVisitor extends ClassVisitor {
    private final String classInternalName;
    private final Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs;
    private boolean hasClinit = false;

    SharedLambdaFieldsInitializerClassVisitor(ClassVisitor classVisitor, String className,
            Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs) {
        super(ASM_VERSION, classVisitor);
        this.generatedFieldNameToInvokeDynamicArgs = generatedFieldNameToInvokeDynamicArgs;
        this.classInternalName = className.replace('.', '/');

        for (var generatedFieldAndInitializerEntry : generatedFieldNameToInvokeDynamicArgs.entrySet()) {
            String fieldName = generatedFieldAndInitializerEntry.getKey();
            var invokeDynamicArgs = generatedFieldAndInitializerEntry.getValue();
            Type fieldDescriptor = invokeDynamicArgs.getFieldDescriptor();
            classVisitor.visitField(Modifier.FINAL | Modifier.PRIVATE | Modifier.STATIC,
                    fieldName, fieldDescriptor.getDescriptor(), invokeDynamicArgs.signature(), null);
        }
    }

    @Override
    public MethodVisitor visitMethod(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final String[] exceptions) {
        if (name.equals("<clinit>")) {
            hasClinit = true;
            return new SharedLambdaFieldsInitializerMethodVisitor(ASM_VERSION,
                    super.visitMethod(access, name, descriptor, signature, exceptions),
                    classInternalName, generatedFieldNameToInvokeDynamicArgs);

        } else {
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }

    @Override
    public void visitEnd() {
        if (!hasClinit) {
            MethodVisitor methodVisitor = visitMethod(Modifier.PUBLIC | Modifier.STATIC, "<clinit>",
                    Type.getMethodDescriptor(Type.VOID_TYPE), null, null);
            methodVisitor.visitCode();
            methodVisitor.visitMaxs(1, 0);
            methodVisitor.visitInsn(Opcodes.RETURN);
            methodVisitor.visitEnd();
        }
        super.visitEnd();
    }
}
