package com.example.instrumentation_example_mod;

import net.bytebuddy.agent.ByteBuddyAgent;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.quiltmc.loader.api.ModContainer;
import org.quiltmc.loader.api.QuiltLoader;
import org.quiltmc.loader.api.entrypoint.PreLaunchEntrypoint;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.ProtectionDomain;
import java.util.Random;

public class ExampleMod implements PreLaunchEntrypoint {
	private boolean didBootstrap = false;

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod name as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger("Example Mod");

	@Override
	public void onPreLaunch(ModContainer mod) {
		LOGGER.info("Instrumentation example mod is in prelaunch!");

		try {
			boostrap();
		} catch (IOException | ClassNotFoundException | NoSuchFieldException | IllegalAccessException |
                 UnmodifiableClassException e) {
			throw new RuntimeException(e);
		}
    }

	private void boostrap() throws IOException, ClassNotFoundException, NoSuchFieldException, IllegalAccessException, UnmodifiableClassException {
		// very unlikely to ever happen but its cheap to guard
		if (didBootstrap) {
			return;
		}
		didBootstrap = true;

		// locate the agent
		InputStream resource;
		if (QuiltLoader.isDevelopmentEnvironment()) {
			resource = new FileInputStream("../InstrumentationGetterSubproject/build/libs/InstrumentationGetterSubproject-1.0.0.jar");
		} else {
			resource = ExampleMod.class.getClassLoader().getResourceAsStream("/META-INF/jars/InstrumentationGetterSubproject-1.0.0.jar");
		}

		// make sure we actually found the agent file
		if (resource == null) {
			throw new IllegalStateException("Could not get instrumentation agent file! Did you rename only part of it or forget to run build at least once in a dev env?");
		}

		// create a directory to hold the agent
		Path agentDir = QuiltLoader.getGlobalCacheDir().resolve(".instrumentation_example_project");
		agentDir.toFile().mkdirs();

		// create the temp file
		Path agentFile = agentDir.resolve("instrumentation_example.jar");
		Files.copy(resource, agentFile, StandardCopyOption.REPLACE_EXISTING);

		// attach the agent to this jvm, this *only* takes a file, hence all the previous work
		// note that ProcessHandle.current().pid().toString() is j9+, on j8 or lower you need to manually parse stuff
		ByteBuddyAgent.attach(agentFile.toFile(), String.valueOf(ProcessHandle.current().pid()));

		// now that our agent is loaded, we need to actually get the instrumentation object
		Class<?> agentClass = Class.forName(
			// name of the agent class
			"com.example.instrumentation_example_mod.InstrumentationGetter",
			// doesn't matter, already initialized
			true,
			// request it from the quilt loader class loader
			QuiltLoader.class.getClassLoader()
		);

		// get the field
		Field field = agentClass.getDeclaredField("instrumentation");
		field.setAccessible(true); // safety:tm:
		// get the instrumentation object
		Instrumentation instrumentation = (Instrumentation) field.get(null);

		ClassFileTransformer logger = new ClassFileTransformer() {
			@Override
			public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
				System.out.println("Got " + className);
				return classfileBuffer;
			}
		};

		// patch Random.nextInt to always return 4
		ClassFileTransformer transformer = new ClassFileTransformer() {
			@Override
			public byte[] transform(Module module, ClassLoader loader, String className, Class<?> classBeingRedefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) throws IllegalClassFormatException {
				// prevent crashing on stray load
				if (!className.equals("java/util/Random"))
					return classfileBuffer;

				ClassNode node = new ClassNode();
				ClassReader reader = new ClassReader(classfileBuffer);
				reader.accept(node, 0);

				MethodNode nextIntMethod = node.methods.stream()
					.filter(methodNode -> methodNode.name.equals("nextInt"))
					.filter(methodNode -> methodNode.desc.equals("()I"))
					.findFirst()
					.orElseThrow();

				InsnList newInstructions = new InsnList();
				newInstructions.add(new LdcInsnNode(4));
				newInstructions.add(new InsnNode(Opcodes.IRETURN));

				nextIntMethod.instructions = newInstructions;

				ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
				node.accept(writer);
				return writer.toByteArray();
			}
		};

		// add our logging transformer
		// normally this could watch for and mass transform newly loaded classes
		// (or mass *re*transform if you give it permission)
		instrumentation.addTransformer(logger);

		// add the transformer, make sure to set canRetransform for already loaded classes, or it'll silently do nothing
		instrumentation.addTransformer(transformer, true);
		instrumentation.retransformClasses(Random.class);
		// because random is already loaded, just remove immediately
		instrumentation.removeTransformer(transformer);

		System.out.println(instrumentation);
		int n = 100;
		while (n-- > 0) {
			System.out.println(new Random().nextInt());
		}
	}
}
