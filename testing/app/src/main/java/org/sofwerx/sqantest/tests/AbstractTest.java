package org.sofwerx.sqantest.tests;

public abstract class AbstractTest {
    private boolean isRunning = false;
    public boolean isRunning() { return isRunning; }

    /**
     * Stops this test
     */
    public void stop() {
        isRunning = false;
    }

    /**
     * Starts this test
     */
    public void start() {
        isRunning = true;
    }

    /**
     * Gets the name of this test
     * @return
     */
    public abstract String getName();
}
