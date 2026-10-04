package open.dolphin.rest;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.Path;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * JAX-RS selects the root resource class by the most specific class-level @Path first.
 * A method path like "/admin" + "/orca/users" is unreachable when another class declares
 * "/admin/orca" (RESTEasy003210 404). Guard against such shadowing for literal paths.
 */
class ResourceRootPathShadowingTest {

    @Test
    void noResourceMethodIsShadowedByMoreSpecificRootPath() {
        Set<Class<?>> classes = new OpenDolphinRestApplication().getClasses();
        List<String> roots = new ArrayList<>();
        for (Class<?> type : classes) {
            Path path = type.getAnnotation(Path.class);
            if (path != null) {
                roots.add(normalize(path.value()));
            }
        }
        List<String> shadowed = new ArrayList<>();
        for (Class<?> type : classes) {
            Path classPath = type.getAnnotation(Path.class);
            if (classPath == null) {
                continue;
            }
            String root = normalize(classPath.value());
            for (Method method : type.getDeclaredMethods()) {
                Path methodPath = method.getAnnotation(Path.class);
                if (methodPath == null) {
                    continue;
                }
                String full = root + normalize(methodPath.value());
                for (String other : roots) {
                    if (other.length() > root.length() && !other.contains("{")
                            && (full.equals(other) || full.startsWith(other + "/"))) {
                        shadowed.add(type.getSimpleName() + "#" + method.getName() + " " + full + " shadowed by " + other);
                    }
                }
            }
        }
        assertThat(shadowed).isEmpty();
    }

    private static String normalize(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty() || v.equals("/")) {
            return "";
        }
        if (!v.startsWith("/")) {
            v = "/" + v;
        }
        return v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
    }
}
