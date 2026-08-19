package synvo.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchitectureBoundaryTests {

	private static final Path SOURCE_ROOT = sourceRoot();

	@Test
	void agentApplicationDoesNotDependOnSurfaceAdapters() throws IOException {
		assertSourcesDoNotContain(
				path -> isUnder(path, "synvo/agent"),
				List.of("synvo.api.", "synvo.lark.", "com.lark.oapi"),
				"The agent application must not depend on H5 or Lark surface adapters");
	}

	@Test
	void conversationSurfacesUseTheSharedApplicationBoundary() throws IOException {
		Path larkHandler = SOURCE_ROOT.resolve("synvo/lark/channel/LarkDirectMessageHandler.java");
		String larkSource = Files.readString(larkHandler);
		assertTrue(larkSource.contains("ConversationRunCoordinator"),
				"Lark Chat must enter through the shared conversation boundary");
		assertFalse(larkSource.contains("SynvoAgentCore"),
				"Lark Chat must not invoke Agent Core orchestration directly");
		assertFalse(larkSource.contains("AgentRuntimeProperties"),
				"Lark Chat must not own a separate conversation timeout policy");

		Path controller = SOURCE_ROOT.resolve("synvo/api/ConversationController.java");
		String controllerSource = Files.readString(controller);
		assertTrue(controllerSource.contains("ConversationRunCoordinator"),
				"H5 must enter through the shared conversation boundary");
		assertFalse(controllerSource.contains("SynvoAgentCore"),
				"H5 must not invoke Agent Core orchestration directly");
	}

	@Test
	void apiControllersDoNotDependOnConcretePersistenceAdapters() throws IOException {
		assertSourcesDoNotContain(
				path -> isUnder(path, "synvo/api") && path.getFileName().toString().endsWith("Controller.java"),
				List.of("synvo.persistence."),
				"API controllers must use application-owned contracts, not persistence adapters");
	}

	@Test
	void vendorTypesStayInsideTheirAdaptersAndConfiguration() throws IOException {
		assertSourcesDoNotContain(
				path -> !isUnder(path, "synvo/agent/model") && !isUnder(path, "synvo/configuration"),
				List.of("org.springframework.ai."),
				"Spring AI types must remain inside the model adapter or configuration");
		assertSourcesDoNotContain(
				path -> !isUnder(path, "synvo/lark"),
				List.of("com.lark.oapi"),
				"Official Lark SDK types must remain inside Lark adapters");
	}

	private static void assertSourcesDoNotContain(
			Predicate<Path> included,
			List<String> forbiddenDependencies,
			String rule) throws IOException {
		try (var paths = Files.walk(SOURCE_ROOT)) {
			for (Path path : paths.filter(Files::isRegularFile).filter(included).toList()) {
				String source = Files.readString(path);
				for (String dependency : forbiddenDependencies) {
					assertFalse(source.contains(dependency),
							() -> rule + ": " + SOURCE_ROOT.relativize(path)
									+ " references " + dependency);
				}
			}
		}
	}

	private static boolean isUnder(Path path, String packagePath) {
		return path.normalize().startsWith(SOURCE_ROOT.resolve(packagePath).normalize());
	}

	private static Path sourceRoot() {
		Path moduleRoot = Path.of("src/main/java");
		if (Files.isDirectory(moduleRoot)) {
			return moduleRoot.toAbsolutePath().normalize();
		}
		Path projectRoot = Path.of("backend/src/main/java");
		if (Files.isDirectory(projectRoot)) {
			return projectRoot.toAbsolutePath().normalize();
		}
		throw new IllegalStateException("Could not locate backend/src/main/java");
	}
}
