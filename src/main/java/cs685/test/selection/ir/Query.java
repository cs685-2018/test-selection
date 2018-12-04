package cs685.test.selection.ir;

/**
 * Stores information on IR queries
 * @author Ryan
 *
 */
public class Query {
	private String classes;
	private String methods;
	private String query;

	public Query(String classes, String methods, String query) {
		this.classes = classes;
		this.methods = methods;
		this.query = query;
	}

	public String getClasses() {
		return this.classes;
	}

	public String getMethods() {
		return this.methods;
	}

	public String getQuery() {
		return this.query;
	}

	public String getCoverages() {
		StringBuilder sb = new StringBuilder();
		sb.append("(");
		sb.append(this.classes);
		sb.append(")#(");
		sb.append(this.methods);
		sb.append(")");
		return sb.toString();
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder();
		sb.append(this.getCoverages());
		sb.append(" covered by query: [");
		sb.append(this.query);
		sb.append("]");
		return sb.toString();
	}
}
