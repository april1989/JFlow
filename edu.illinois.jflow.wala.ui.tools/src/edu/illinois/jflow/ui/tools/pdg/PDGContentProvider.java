package edu.illinois.jflow.ui.tools.pdg;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.eclipse.jface.viewers.Viewer;
import org.eclipse.zest.core.viewers.IGraphEntityContentProvider;

import com.ibm.wala.util.graph.Graph;

import edu.illinois.jflow.jflow.wala.dataflowanalysis.PDGNode;

public class PDGContentProvider implements IGraphEntityContentProvider {

	private Graph<PDGNode> graph;

	@SuppressWarnings("unchecked")
	@Override
	public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
		viewer.refresh();
		graph= (Graph<PDGNode>)newInput;
	}

	@Override
	public Object[] getElements(Object inputElement) {
		if (inputElement instanceof Graph) {
			Graph<?> graph= (Graph<?>)inputElement;
			List<Object> nodes= new ArrayList<Object>();
			for (Object object : graph) {
				nodes.add(object);
			}
			return nodes.toArray();
		}
		return null;
	}

	@Override
	public Object[] getConnectedTo(Object entity) {
		Iterator<PDGNode> succNodes= graph.getSuccNodes((PDGNode)entity);
		Set<Object> nodes= new HashSet<Object>();
		while (succNodes.hasNext()) {
			nodes.add(succNodes.next());
		}
		return nodes.toArray();
	}

	@Override
	public void dispose() {
	}

}
