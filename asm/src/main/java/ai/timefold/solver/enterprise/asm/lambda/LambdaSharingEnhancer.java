package ai.timefold.solver.enterprise.asm.lambda;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

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
        ClassWriter classWriter = new ClassWriter(Opcodes.ASM9);
        ClassVisitor classVisitor =
                new BytecodeRecordingClassVisitor(classWriter, methodNameToCanonicalMethod);
        ClassReader classReader = new ClassReader(inputBytes);
        classReader.accept(classVisitor, Opcodes.ASM9);

        // Second pass: replace lambdas with static field reads
        inputBytes = classWriter.toByteArray();
        classWriter = new ClassWriter(Opcodes.ASM9);
        classVisitor =
                new LambdaSharingClassVisitor(classWriter, name, methodNameToCanonicalMethod,
                        generatedFieldNameToInvokeDynamicArgs);
        classReader = new ClassReader(inputBytes);
        classReader.accept(classVisitor, Opcodes.ASM9);
        inputBytes = classWriter.toByteArray();

        // Final pass: initialize static fields with recorded invokedynamic
        classWriter = new ClassWriter(Opcodes.ASM9);
        classVisitor =
                new SharedLambdaFieldsInitializerClassVisitor(classWriter, name,
                        generatedFieldNameToInvokeDynamicArgs);
        classReader = new ClassReader(inputBytes);
        classReader.accept(classVisitor, Opcodes.ASM9);
        return classWriter.toByteArray();
    }
}
