package ai.timefold.solver.enterprise.asm.lambda;

import static ai.timefold.solver.enterprise.asm.ASMConstants.ASM_VERSION;

import java.util.HashMap;
import java.util.Map;

import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;

final class BytecodeRecordingClassVisitor extends ClassVisitor {
    private final Map<String, String> methodNameToBytecode = new HashMap<>();
    private final Map<String, String> methodIdToCanonicalMethodId;

    BytecodeRecordingClassVisitor(ClassVisitor classVisitor, Map<String, String> methodIdToCanonicalMethodId) {
        super(ASM_VERSION, classVisitor);
        this.methodIdToCanonicalMethodId = methodIdToCanonicalMethodId;
    }

    @Override
    public MethodVisitor visitMethod(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final String[] exceptions) {
        return new BytecodeRecordingMethodVisitor(super.visitMethod(access, name, descriptor, signature, exceptions),
                (bytecode) -> {
                    String key = LambdaSharingMethodVisitor.getMethodId(name, descriptor);
                    for (var existingBytecodeEntry : methodNameToBytecode.entrySet()) {
                        String existingKey = existingBytecodeEntry.getKey();
                        String existingMethodDescriptor = LambdaSharingMethodVisitor.getDescriptor(existingKey);
                        if (!descriptor.equals(existingMethodDescriptor)) {
                            continue;
                        }
                        String existingBytecode = existingBytecodeEntry.getValue();
                        if (existingBytecode.equals(bytecode)) {
                            methodIdToCanonicalMethodId.put(key, existingKey);
                            return;
                        }
                    }
                    methodNameToBytecode.put(key, bytecode);
                    methodIdToCanonicalMethodId.put(key, key);
                });
    }

    @Override
    public void visitEnd() {
        super.visitEnd();
    }
}