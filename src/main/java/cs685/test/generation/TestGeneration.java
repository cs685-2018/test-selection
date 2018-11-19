package cs685.test.generation;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import com.github.jenkins.lastchanges.model.LastChanges;

public class TestGeneration {

    private HashMap<String, List<String>> map;
    
    public TestGeneration(HashMap<String, List<String>> map) {
        this.map = map;
    }

    public Set<String> getClassNames() {
    	return this.map.keySet();
    }
    
    public List<String> getMethodNames(String className) {
    	if (this.map.containsKey(className)) {
    		return this.map.get(className);
    	} else {
    		return Collections.emptyList();
    	}
    }
	
	public List<String> getChanges(){
		return LastChanges.getCommits();
		
	public String getDifferences (){
		return LastChanges.getDiff();
}
