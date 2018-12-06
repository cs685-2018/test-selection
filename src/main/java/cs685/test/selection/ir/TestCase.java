package cs685.test.selection.ir;

public class TestCase {
	private final Integer id;
	private final String className;
	private final String methodName;
	private final String content;
	
	public TestCase(Integer id, String className, String methodName, String content) {
		this.id = id;
		this.className = className;
		this.methodName = methodName;
		this.content = content;
	}
	
	public Integer getId() {
		return this.id;
	}
	
	public String getClassName() {
		return this.className;
	}
	
	public String getMethodName() {
		return this.methodName;
	}
	
	public String getContent() {
		return this.content;
	}
}
