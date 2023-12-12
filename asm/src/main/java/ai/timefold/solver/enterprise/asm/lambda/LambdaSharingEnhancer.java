package ai.timefold.solver.enterprise.asm.lambda;

import static ai.timefold.solver.enterprise.asm.ASMConstants.ASM_VERSION;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;

public class LambdaSharingEnhancer {
    public static byte[] shareLambdasInClass(Class<?> clazz) throws IOException {
        try (InputStream bytecodeInputStream = ClassLoader.getSystemResourceAsStream(
                clazz.getName().replace('.', '/') + ".class")) {
            if (bytecodeInputStream == null) {
                throw new IllegalArgumentException("Could not locate bytecode for class (" + clazz + ").");
            }
            return shareLambdasInBytecode(clazz.getName(), bytecodeInputStream.readAllBytes());
        }
    }

    public static byte[] shareLambdasInBytecode(String name, byte[] inputBytes) {
        Map<String, String> methodNameToCanonicalMethod = new HashMap<>();
        Map<String, LambdaSharingMethodVisitor.InvokeDynamicArgs> generatedFieldNameToInvokeDynamicArgs = new LinkedHashMap<>();

        // First pass: record method bytecode
        ClassWriter classWriter = new ClassWriter(ASM_VERSION);
        ClassVisitor classVisitor =
                new BytecodeRecordingClassVisitor(classWriter, methodNameToCanonicalMethod);
        ClassReader classReader = new ClassReader(inputBytes);
        classReader.accept(classVisitor, ASM_VERSION);

        // Second pass: replace lambdas with static field reads
        inputBytes = classWriter.toByteArray();
        classWriter = new ClassWriter(ASM_VERSION);
        classVisitor =
                new LambdaSharingClassVisitor(classWriter, name, methodNameToCanonicalMethod,
                        generatedFieldNameToInvokeDynamicArgs);
        classReader = new ClassReader(inputBytes);
        classReader.accept(classVisitor, ASM_VERSION);
        inputBytes = classWriter.toByteArray();

        // Final pass: initialize static fields with recorded invokedynamic
        classWriter = new ClassWriter(ASM_VERSION);
        classVisitor =
                new SharedLambdaFieldsInitializerClassVisitor(classWriter, name,
                        generatedFieldNameToInvokeDynamicArgs);
        classReader = new ClassReader(inputBytes);
        classReader.accept(classVisitor, ASM_VERSION);
        return classWriter.toByteArray();
    }
}
