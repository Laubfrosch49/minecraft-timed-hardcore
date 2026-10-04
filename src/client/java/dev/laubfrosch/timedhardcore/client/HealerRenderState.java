package dev.laubfrosch.timedhardcore.client;

/** Extra field on the render state of traders: is this a Wandering Healer? */
public interface HealerRenderState {

	boolean timedhardcore$isHealer();

	void timedhardcore$setHealer(boolean healer);
}
