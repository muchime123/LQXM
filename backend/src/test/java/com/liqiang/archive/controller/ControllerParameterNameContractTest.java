package com.liqiang.archive.controller;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 编译参数契约：控制器方法的参数必须保留参数名（javac {@code -parameters}）。
 *
 * <p>背景：pom 的 maven-compiler-plugin 已开启 {@code <parameters>true</parameters>}，
 * 但若 target/classes 先被 IDE 增量编译产物写入、mvn 又判定「无变更」而跳过重编译，
 * 打进 jar 的类会丢失 MethodParameters 属性；此时未显式写 name 的
 * {@code @RequestParam} / {@code @PathVariable}（本仓库共 285 处）会在运行期抛
 * {@code Name for argument of type [...] not specified, and parameter name information not available via reflection}，
 * 表现为接口 400/500。本测试在构建期直接断言该属性存在，避免静默退化。
 *
 * <p>根治手段是打包一律使用 {@code mvn -o clean package}（clean 强制全量重编译）。
 */
class ControllerParameterNameContractTest {

    private static final String CONTROLLER_PACKAGE = "com.liqiang.archive.controller";

    /** 低于该数量说明扫描本身失效（而非通过），防止空跑假绿。 */
    private static final int MIN_CONTROLLER_COUNT = 20;

    @Test
    void allControllerMethodParametersKeepNames() {
        List<String> offenders = new ArrayList<>();
        int scanned = 0;

        for (String className : controllerClassNames()) {
            Class<?> type;
            try {
                type = Class.forName(className);
            } catch (ClassNotFoundException | NoClassDefFoundError e) {
                offenders.add(className + " 无法加载：" + e);
                continue;
            }
            scanned++;
            for (Method method : type.getDeclaredMethods()) {
                if (method.isSynthetic() || method.isBridge()) {
                    continue;
                }
                Parameter[] parameters = method.getParameters();
                for (int i = 0; i < parameters.length; i++) {
                    if (!parameters[i].isNamePresent()) {
                        offenders.add(type.getSimpleName() + "#" + method.getName()
                                + " 第 " + (i + 1) + " 个参数（" + parameters[i].getType().getSimpleName() + "）");
                    }
                }
            }
        }

        assertTrue(scanned >= MIN_CONTROLLER_COUNT,
                "只扫描到 " + scanned + " 个控制器类（预期 ≥ " + MIN_CONTROLLER_COUNT
                        + "），扫描逻辑可能已失效，请检查包名或 classpath");

        assertTrue(offenders.isEmpty(),
                "以下控制器参数丢失参数名（需以 mvn -o clean package 全量重编译，勿复用 IDE 增量编译产物）：\n  "
                        + String.join("\n  ", offenders));
    }

    /** 枚举控制器包下的顶层类名；{@code *} 通配不跨目录，且跳过内部/匿名类。 */
    private static List<String> controllerClassNames() {
        List<String> names = new ArrayList<>();
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            for (Resource resource : resolver.getResources("classpath*:" + CONTROLLER_PACKAGE.replace('.', '/') + "/*.class")) {
                String filename = resource.getFilename();
                if (filename == null || filename.contains("$")) {
                    continue;
                }
                names.add(CONTROLLER_PACKAGE + "." + filename.substring(0, filename.length() - ".class".length()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("扫描控制器包失败：" + CONTROLLER_PACKAGE, e);
        }
        return names;
    }
}
