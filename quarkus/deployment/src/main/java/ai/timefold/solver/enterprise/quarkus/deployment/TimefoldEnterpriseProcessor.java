package ai.timefold.solver.enterprise.quarkus.deployment;

import ai.timefold.solver.core.api.score.stream.ConstraintProvider;
import ai.timefold.solver.enterprise.asm.lambda.LambdaSharingEnhancer;

import org.jboss.jandex.ClassInfo;

import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;

public class TimefoldEnterpriseProcessor {
    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem("timefold-solver-enterprise");
    }

    @BuildStep
    void optimizeConstraintProvider(CombinedIndexBuildItem combinedIndex,
            BuildProducer<BytecodeTransformerBuildItem> transformers) {
        combinedIndex.getIndex().getAllKnownImplementors(ConstraintProvider.class)
                .forEach(classInfo -> shareLambdas(classInfo, transformers));
    }

    private void shareLambdas(ClassInfo classInfo, BuildProducer<BytecodeTransformerBuildItem> transformers) {
        transformers.produce(new BytecodeTransformerBuildItem.Builder()
                .setClassToTransform(classInfo.name().toString())
                .setInputTransformer(LambdaSharingEnhancer::shareLambdasInBytecode)
                .build());
    }
}
