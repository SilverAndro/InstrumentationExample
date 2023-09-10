package com.example.instrumentation_example_mod;

import java.lang.instrument.Instrumentation;

public class InstrumentationGetter {
	// expose the instrumentation object as a public static field so we can extract it
	public static Instrumentation instrumentation;

	// this is run when the agent is loaded
	public static void agentmain(String agentArgs, Instrumentation instrumentation) {
		InstrumentationGetter.instrumentation = instrumentation;
	}
}
