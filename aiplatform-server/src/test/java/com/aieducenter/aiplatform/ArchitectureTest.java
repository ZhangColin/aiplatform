package com.aieducenter.aiplatform;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;

import com.cartisan.test.archunit.CartisanArchRules;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import com.aieducenter.aiplatform.architecture.PartitionRules;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 架构守护测试：cartisan 全量规则（分层 / 命名 / 禁止 / 编码规范）+ 本项目分区规则。
 * 规范正本：docs/guide/限界上下文代码编写规范.md §10；分区规则见 B0 蓝图 §1。
 */
@AnalyzeClasses(packages = "com.aieducenter.aiplatform", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest extends CartisanArchRules {

    @ArchTest
    static final ArchRule base_should_not_depend_on_business = PartitionRules.BASE_MUST_NOT_DEPEND_ON_BUSINESS;

    /**
     * #165 启动零写单价表：单价表写口唯一化到管理 API，初始化走幂等签名脚本——
     * base.metering 不得再挂启动播种 Runner（Seeder 与管理 API 并行＝「重启回种子/
     * 双写源」事故口）。workspace 恢复类 Runner 在别的包，不受本规则约束。
     */
    @ArchTest
    static final ArchRule metering_should_not_seed_on_startup = noClasses()
            .that().resideInAPackage("..base.metering..")
            .should().implement(ApplicationRunner.class)
            .orShould().implement(CommandLineRunner.class)
            .because("#165 启动零写单价表（写口唯一化到管理 API）");
}
