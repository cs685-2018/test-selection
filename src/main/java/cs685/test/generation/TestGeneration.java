package cs685.test.generation;

public class TestGeneration {

    private final int classesNumber;
    private final int linesNumber;

    public TestGeneration(int classesNumber, int linesNumber) {
        this.classesNumber = classesNumber;
        this.linesNumber = linesNumber;
    }

    public int getClassesNumber() {
        return classesNumber;
    }

    public int getLinesNumber() {
        return linesNumber;
    }
}
