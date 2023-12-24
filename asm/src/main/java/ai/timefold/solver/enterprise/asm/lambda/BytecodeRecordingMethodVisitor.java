package ai.timefold.solver.enterprise.asm.lambda;

import static ai.timefold.solver.enterprise.asm.ASMConstants.ASM_VERSION;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.function.Consumer;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.commons.InstructionAdapter;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;
import org.objectweb.asm.util.TraceMethodVisitor;

final class BytecodeRecordingMethodVisitor extends InstructionAdapter {
    public BytecodeRecordingMethodVisitor(MethodVisitor methodVisitor, Consumer<String> methodBytecodeConsumer) {
        super(ASM_VERSION, getBytecodeRecorder(methodVisitor, methodBytecodeConsumer));
    }

    private static MethodVisitor getBytecodeRecorder(final MethodVisitor methodVisitor,
            Consumer<String> methodBytecodeConsumer) {
        Printer p = new Textifier(ASM_VERSION) {
            @Override
            public void visitLineNumber(int number, Label label) {
                // Record nothing; metadata
            }

            @Override
            public void visitLocalVariable(final String name,
                    final String descriptor,
                    final String signature,
                    final Label start,
                    final Label end,
                    final int index) {
                // Record nothing; metadata
            }

            @Override
            public void visitMethodEnd() {
                StringWriter out = new StringWriter();
                print(new PrintWriter(out));
                methodBytecodeConsumer.accept(out.toString());
            }
        };
        return new TraceMethodVisitor(methodVisitor, p);
    }
}
