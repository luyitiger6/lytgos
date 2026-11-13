package io.lytgos.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 四条不可越线的架构约束，是 {@code AGENTS.md} 4.1 / 4.2 的机器化表达。
 *
 * <p>为什么要有这个类：工具执行必须 100% 由 {@code ToolExecutor} 发起，这条产品红线
 * 靠 code review 守不住——{@code ChatClient} 加上去，编译、单测、功能验收全都不会红，
 * 只有治理（配额、白名单、沙箱、审计）被静默绕过。所以把它变成测试：违规即构建失败。</p>
 *
 * <p>04 号闸门直接执行本类（{@code mvn -pl lytgos-test -am test -Dtest=ArchitectureTest}）。
 * 必须带 {@code -am}：本模块没有生产代码，规则扫描的是其余模块的字节码；少了它扫不到类，
 * 测试会静默通过——这比失败更危险。</p>
 */
@AnalyzeClasses(packages = "io.lytgos", importOptions = ImportOption.DoNotIncludeTests.class)
public class ArchitectureTest {

    /**
     * Spring Boot 条件装配注解。按类名匹配而不是引用类型：{@code spring-boot-autoconfigure}
     * 不在本模块的测试 classpath 上，引用会编译不过；按名匹配连「依赖没引进来」也能防住。
     */
    private static final DescribedPredicate<JavaAnnotation<?>> CONDITIONAL_ASSEMBLY_ANNOTATIONS =
            DescribedPredicate.describe("Spring Boot 条件装配注解", annotation -> {
                String name = annotation.getRawType().getName();
                return name.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnBean")
                        || name.equals("org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean");
            });

    /** 按类型向容器要对象的调用：一个按接口类型取全量 Bean，一个用来取 {@code ObjectProvider}。 */
    private static final DescribedPredicate<JavaMethodCall> TYPE_SCANNING_CALLS =
            DescribedPredicate.describe("按类型扫描容器（getBeansOfType / getBeanProvider）", call -> {
                String name = call.getTarget().getName();
                return name.equals("getBeansOfType") || name.equals("getBeanProvider");
            });

    /**
     * 禁 {@code ChatClient} 及其 Advisor 包。
     *
     * <p>{@code ChatClient} 内部挂 {@code ToolCallingAdvisor}，会自行驱动工具调用循环；
     * 工具一被自动执行，治理就挂不上执行路径。需要模型时只允许 {@code ChatModel.call(prompt)}，
     * 它返回模型原样响应，不执行工具。</p>
     */
    @ArchTest
    static final ArchRule noChatClient =
            noClasses().should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.chat.client..")
                    .because("ChatClient 会自动驱动工具调用循环，工具执行只能经 ToolExecutor 发起");

    /** 禁 {@code ToolCallingManager}：Spring AI 的自动工具执行器本体。 */
    @ArchTest
    static final ArchRule noToolCallingManager =
            noClasses().should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.springframework.ai.model.tool.ToolCallingManager")
                    .because("自动工具执行器一旦被引用，工具调用就绕过了 ToolExecutor 的配额、沙箱与审计");

    /**
     * 禁条件装配注解：provider 映射必须是启动时从配置构造的显式 Map。
     *
     * <p>用 {@code @ConditionalOnBean} 这类注解，多引一个 starter 就会凭空多出一个 provider，
     * 配置驱动退化成 classpath 驱动。</p>
     */
    @ArchTest
    static final ArchRule noConditionalAssembly =
            noClasses().should().beAnnotatedWith(CONDITIONAL_ASSEMBLY_ANNOTATIONS)
                    .because("provider 必须是配置里的显式映射，不能随 classpath 变化");

    /**
     * 禁类型扫描：不按类型向容器要模型。
     *
     * <p>{@code ObjectProvider<ChatModel>} 的泛型在字节码层面被擦除，没法只禁这一个类型参数；
     * 因此整类禁用 {@code ObjectProvider} 与 {@code getBeansOfType} / {@code getBeanProvider}。
     * 要模型只有一条路：{@code ProviderRegistry.require(name)}。</p>
     */
    @ArchTest
    static final ArchRule noTypeScanning =
            CompositeArchRule.of(noClasses().should().callMethodWhere(TYPE_SCANNING_CALLS))
                    .and(noClasses().should().dependOnClassesThat()
                            .haveFullyQualifiedName("org.springframework.beans.factory.ObjectProvider"))
                    .because("要模型只能走 ProviderRegistry.require(name)，不能按类型向容器扫描");
}
