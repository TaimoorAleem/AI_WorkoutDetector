package dev.proprio.coreapi.unit;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RestController;

/**
 * Contract-first guard: every operationId in contracts/openapi/core-api.yaml must exist as a method
 * on a generated API interface, and every such method must be implemented by a @RestController.
 */
class ContractCoverageTest {

  @Test
  void everyContractOperationIsImplementedByAController() throws Exception {
    Path yaml = Path.of(System.getProperty("proprio.contracts.dir"), "openapi", "core-api.yaml");
    JsonNode spec = new YAMLMapper().readTree(yaml.toFile());

    Set<String> operationIds = new TreeSet<>();
    for (JsonNode path : spec.get("paths")) {
      for (String verb : new String[] {"get", "post", "put", "delete", "patch"}) {
        if (path.has(verb)) {
          operationIds.add(path.get(verb).get("operationId").asText());
        }
      }
    }
    assertThat(operationIds).hasSize(17);

    // Concrete controllers in our package.
    var scanner = new ClassPathScanningCandidateComponentProvider(false);
    scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
    Set<Class<?>> controllers = new HashSet<>();
    for (var bd : scanner.findCandidateComponents("dev.proprio.coreapi")) {
      controllers.add(Class.forName(((AnnotatedBeanDefinition) bd).getMetadata().getClassName()));
    }
    assertThat(controllers).isNotEmpty();

    // operationId -> declared on a generated *Api interface and implemented (non-abstract)
    // somewhere
    Set<String> implemented = new TreeSet<>();
    Set<String> declared = new TreeSet<>();
    for (Class<?> controller : controllers) {
      for (Class<?> api : controller.getInterfaces()) {
        if (!api.getPackageName().equals("dev.proprio.coreapi.api")) {
          continue;
        }
        for (Method m : api.getDeclaredMethods()) {
          if (Modifier.isAbstract(m.getModifiers())) {
            declared.add(m.getName());
            Method impl = controller.getMethod(m.getName(), m.getParameterTypes());
            assertThat(Modifier.isAbstract(impl.getModifiers())).isFalse();
            implemented.add(m.getName());
          }
        }
      }
    }
    assertThat(implemented)
        .as("operation ids implemented by controllers")
        .containsExactlyInAnyOrderElementsOf(operationIds);
    assertThat(declared).isEqualTo(operationIds);
  }
}
