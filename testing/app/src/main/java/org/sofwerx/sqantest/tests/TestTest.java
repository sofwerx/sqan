package org.sofwerx.sqantest.tests;

/**
 * A simple test to test the testing mechanism
 */
public class TestTest extends AbstractTest {
    @Override
    public void stop() {
        //ignore
        super.stop();
    }

    @Override
    public void start() {
        super.start();
        //ignore
    }

    @Override
    public String getName() {
        return "Test Test";
    }
}
