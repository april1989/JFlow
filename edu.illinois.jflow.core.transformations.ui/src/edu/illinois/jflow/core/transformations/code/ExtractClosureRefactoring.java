/**
 * This class derives
 * from {@link org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring} and is
 * licensed under the Eclipse Public License.
 */
package edu.illinois.jflow.core.transformations.code;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import org.eclipse.core.runtime.SubProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeHierarchy;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CastExpression;
import org.eclipse.jdt.core.dom.ChildListPropertyDescriptor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IExtendedModifier;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite.ImportRewriteContext;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.refactoring.CompilationUnitChange;
import org.eclipse.jdt.internal.corext.codemanipulation.ContextSensitiveImportRewriteContext;
import org.eclipse.jdt.internal.corext.codemanipulation.StubUtility;
import org.eclipse.jdt.internal.corext.dom.ASTNodeFactory;
import org.eclipse.jdt.internal.corext.dom.ASTNodes;
import org.eclipse.jdt.internal.corext.dom.LinkedNodeFinder;
import org.eclipse.jdt.internal.corext.dom.Selection;
import org.eclipse.jdt.internal.corext.refactoring.Checks;
import org.eclipse.jdt.internal.corext.refactoring.ParameterInfo;
import org.eclipse.jdt.internal.corext.refactoring.util.RefactoringASTParser;
import org.eclipse.jdt.internal.corext.refactoring.util.ResourceUtil;
import org.eclipse.jdt.internal.corext.util.Messages;
import org.eclipse.jdt.internal.ui.viewsupport.BasicElementLabels;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ResourceChangeChecker;
import org.eclipse.text.edits.TextEditGroup;

import com.ibm.wala.cast.java.ipa.callgraph.JavaSourceAnalysisScope;
import com.ibm.wala.cast.java.translator.jdt.JDTIdentityMapper;
import com.ibm.wala.classLoader.IMethod;
import com.ibm.wala.client.AbstractAnalysisEngine;
import com.ibm.wala.ipa.callgraph.AnalysisCache;
import com.ibm.wala.ipa.callgraph.AnalysisOptions;
import com.ibm.wala.ipa.callgraph.impl.Everywhere;
import com.ibm.wala.ipa.cha.IClassHierarchy;
import com.ibm.wala.shrikeCT.InvalidClassFileException;
import com.ibm.wala.ssa.IR;
import com.ibm.wala.types.MethodReference;
import com.ibm.wala.util.CancelException;

import edu.illinois.jflow.jflow.wala.dataflowanalysis.PDGExtractClosureAnalyzer;
import edu.illinois.jflow.jflow.wala.dataflowanalysis.ProgramDependenceGraph;
import edu.illinois.jflow.wala.utils.EclipseProjectAnalysisEngine;

/**
 * Extracts a closure in a compilation unit based on a text selection range.
 * 
 * @author Nicholas Chen
 */
@SuppressWarnings("restriction")
public class ExtractClosureRefactoring extends Refactoring {

	/**
	 * Locates the existing DataflowChannels and the names and finds new unique names for them.
	 * 
	 */
	private static final class DataflowChannelVisitor extends ASTVisitor {
		private int largestChannelNumber= 0;

		private IProgressMonitor pm;

		public DataflowChannelVisitor(IProgressMonitor pm) {
			this.pm= pm;
		}

		@Override
		public boolean visit(VariableDeclarationStatement node) {
			ITypeBinding binding= node.getType().resolveBinding();
			IJavaElement javaElement= binding.getJavaElement();
			if (javaElement != null) {

				IType type= (IType)binding.getJavaElement();
				try {
					ITypeHierarchy supertypeHierarchy= type.newSupertypeHierarchy(pm);
					IType[] allInterfaces= supertypeHierarchy.getAllInterfaces();
					for (IType interfaceType : allInterfaces) {
						String fullyQualifiedName= interfaceType.getFullyQualifiedName();
						if (fullyQualifiedName.equals(DATAFLOWQUEUE_INTERFACE)) {
							// This is safe because VariableDeclarationStatement.fragment() returns a list of VariableDeclarationFragment
							@SuppressWarnings("unchecked")
							List<VariableDeclarationFragment> fragments= (List<VariableDeclarationFragment>)node.fragments();
							for (VariableDeclarationFragment fragment : fragments) {
								String identifier= fragment.getName().getIdentifier();
								Pattern pattern= Pattern.compile(GENERIC_CHANNEL_NAME + "(\\d+)");
								Matcher matcher= pattern.matcher(identifier);
								if (matcher.find()) {
									String group= matcher.group(1);
									determineLargestCount(group);
								}
							}
						}

					}
				} catch (JavaModelException e) {
					e.printStackTrace();
				}

			}
			return super.visit(node);
		}

		private void determineLargestCount(String channelName) {
			int parseInt= Integer.parseInt(channelName);
			largestChannelNumber= parseInt > getLargestChannelNumber() ? parseInt : getLargestChannelNumber();
		}

		public int getLargestChannelNumber() {
			return largestChannelNumber;
		}
	}

	/**
	 * A structure to group together all the useful information for each stage. This structure is
	 * primarily focused only for the transformation component. The analysis component uses a
	 * different scheme to facilitate analysis and testing (less dependency on JDT).
	 * 
	 * @author nchen
	 * 
	 */
	private final class Stage {
		final AnnotatedStage stage;

		final ExtractClosureAnalyzer analyzer;

		PDGExtractClosureAnalyzer pdgAnalyzer;

		List<ParameterInfo> parameterInfo;

		Stage(AnnotatedStage stage, ExtractClosureAnalyzer analyzer) {
			this.stage= stage;
			this.analyzer= analyzer;
		}

		public ExtractClosureAnalyzer getAnalyzer() {
			return analyzer;
		}

		public AnnotatedStage getStage() {
			return stage;
		}

		public PDGExtractClosureAnalyzer getPdgAnalyzer() {
			return pdgAnalyzer;
		}

		public void setPdgAnalyzer(PDGExtractClosureAnalyzer pdgAnalyzer) {
			this.pdgAnalyzer= pdgAnalyzer;
		}

		public List<ParameterInfo> getParameterInfo() {
			return parameterInfo;
		}

		private void initializeParameterInfos() {
			List<IVariableBinding> arguments= pdgAnalyzer.getInputBindings(analyzer.getSelectedNodes());
			parameterInfo= new ArrayList<ParameterInfo>(arguments.size());
			ASTNode root= analyzer.getEnclosingBodyDeclaration();

			ParameterInfo vararg= null;
			int index= 0;
			for (IVariableBinding argument : arguments) {
				if (argument == null)
					continue;
				VariableDeclaration declaration= ASTNodes.findVariableDeclaration(argument, root);
				boolean isVarargs= declaration instanceof SingleVariableDeclaration
						? ((SingleVariableDeclaration)declaration).isVarargs()
						: false;
				ParameterInfo info= new ParameterInfo(argument, getType(declaration, isVarargs), argument.getName(), index++);
				if (isVarargs) {
					vararg= info;
				} else {
					parameterInfo.add(info);
				}
			}
			if (vararg != null) {
				parameterInfo.add(vararg);
			}
		}

		private String getType(VariableDeclaration declaration, boolean isVarargs) {
			String type= ASTNodes.asString(ASTNodeFactory.newType(declaration.getAST(), declaration, fImportRewriter, new ContextSensitiveImportRewriteContext(declaration, fImportRewriter)));
			if (isVarargs)
				return type + ParameterInfo.ELLIPSIS;
			else
				return type;
		}
	}

	private ICompilationUnit fCUnit;

	private CompilationUnit fRoot;

	private AST fAST;

	private ImportRewrite fImportRewriter;

	private int fSelectionStart;

	private int fSelectionLength;

	private ASTRewrite fRewriter;

	//XXX:Remove me
	private List<ParameterInfo> fParameterInfos;

	private Map<Integer, Stage> stages;

	private IDocument fDoc;

	private static final String EMPTY= ""; //$NON-NLS-1$

	// This section is specific to the API for GPars Dataflow

	public static final String CLOSURE_PARAMETER_NAME= "arguments"; //$NON-NLS-1$

	public static final String CLOSURE_PARAMETER_TYPE= "Object"; //$NON-NLS-1$

	public static final String CLOSURE_METHOD= "doRun"; //$NON-NLS-1$

	public static final String CLOSURE_INVOCATION_METHOD_NAME= "call"; //$NON-NLS-1$

	public static final String CLOSURE_PACKAGE= "groovyx.gpars"; //$NON-NLS-1$

	public static final String CLOSURE_TYPE= "DataflowMessagingRunnable"; //$NON-NLS-1$

	public static final String CLOSURE_QUALIFIED_TYPE= CLOSURE_PACKAGE + "." + CLOSURE_TYPE; //$NON-NLS-1$

	public static final String DATAFLOWQUEUE_TYPE= "groovyx.gpars.dataflow.DataflowQueue"; //$NON-NLS-1$

	public static final String DATAFLOWQUEUE_INTERFACE= "groovyx.gpars.dataflow.DataflowChannel"; //$NON-NLS-1$

	public static final String DATAFLOWQUEUE_PUT_METHOD= "bind"; //$NON-NLS-1$

	public static final String GENERIC_CHANNEL_NAME= "channel"; //$NON-NLS-1$


	/**
	 * Creates a new extract closure refactoring
	 * 
	 * @param unit the compilation unit, or <code>null</code> if invoked by scripting
	 * @param doc the document of the current editor
	 * @param selectionStart selection start
	 * @param selectionLength selection end
	 */
	public ExtractClosureRefactoring(ICompilationUnit unit, IDocument doc, int selectionStart, int selectionLength) {
		fCUnit= unit;
		fDoc= doc;
		fRoot= null;
		fSelectionStart= selectionStart;
		fSelectionLength= selectionLength;
		stages= new HashMap<Integer, Stage>();
	}

	@Override
	public String getName() {
		return JFlowRefactoringCoreMessages.ExtractClosureRefactoring_name;
	}

	/**
	 * Checks if the refactoring can be activated. Activation typically means, if a corresponding
	 * menu entry can be added to the UI.
	 * 
	 * @param pm a progress monitor to report progress during activation checking.
	 * @return the refactoring status describing the result of the activation check.
	 * @throws CoreException if checking fails
	 */
	@Override
	public RefactoringStatus checkInitialConditions(IProgressMonitor pm) throws CoreException {
		RefactoringStatus result= new RefactoringStatus();
		pm.beginTask("", 100); //$NON-NLS-1$

		IFile[] changedFiles= ResourceUtil.getFiles(new ICompilationUnit[] { fCUnit });
		result.merge(Checks.validateModifiesFiles(changedFiles, getValidationContext()));
		if (result.hasFatalError())
			return result;
		result.merge(ResourceChangeChecker.checkFilesToBeChanged(changedFiles, new SubProgressMonitor(pm, 1)));

		if (fRoot == null) {
			fRoot= RefactoringASTParser.parseWithASTProvider(fCUnit, true, new SubProgressMonitor(pm, 99));
		}
		fImportRewriter= StubUtility.createImportRewrite(fRoot, true);

		fAST= fRoot.getAST();

		initializeStageInfo();

		for (Stage stage : stages.values()) {
			ExtractClosureAnalyzer analyzer= stage.getAnalyzer();
			fRoot.accept(analyzer);
			// XXX: Remove the fImportRewriter parameter
			result.merge(analyzer.checkInitialConditions(fImportRewriter));
		}

		if (result.hasFatalError())
			return result;

		// If we don't have any errors at this point, we can initialize the heavy-lifting parts
		try {
			createPDGAnalyzers();
		} catch (Exception e) {
			e.printStackTrace();
		}

		for (Stage stage : stages.values()) {
			PDGExtractClosureAnalyzer analyzer= stage.getPdgAnalyzer();
			analyzer.analyzeSelection();
			stage.initializeParameterInfos();
		}

		return result;
	}

	private void initializeStageInfo() throws CoreException {
		MethodDeclaration methodDeclaration= locateSelectedMethod();
		AnnotatedStagesFinder locator= new AnnotatedStagesFinder(fRoot, fDoc, methodDeclaration);

		List<AnnotatedStage> annotatedStages= locator.locateStages();
		for (int stageNumber= 0; stageNumber < annotatedStages.size(); stageNumber++) {
			AnnotatedStage stage= annotatedStages.get(stageNumber);
			ExtractClosureAnalyzer analyzer= new ExtractClosureAnalyzer(fCUnit, stage.getSelection());
			Stage stageInfo= new Stage(stage, analyzer);
			stages.put(stageNumber, stageInfo);
		}
	}

	private MethodDeclaration locateSelectedMethod() {
		NodeFinder nodeFinder= new NodeFinder(fRoot, fSelectionStart, fSelectionLength);
		ASTNode coveringNode= nodeFinder.getCoveringNode();
		MethodDeclaration methodDeclaration= (MethodDeclaration)ASTNodes.getParent(coveringNode, MethodDeclaration.class);
		return methodDeclaration;
	}

	private void createPDGAnalyzers() throws IOException, CoreException, InvalidClassFileException, IllegalArgumentException, CancelException {
		// Set up the analysis engine
		AbstractAnalysisEngine engine= new EclipseProjectAnalysisEngine(fCUnit.getJavaProject());
		engine.buildAnalysisScope();
		final IClassHierarchy classHierarchy= engine.buildClassHierarchy();
		final AnalysisOptions options= new AnalysisOptions();
		final AnalysisCache cache= engine.makeDefaultCache();

		// Get the IR for the selected method
		// Since all the stages are going to be in the same method, just use the first ExtractClosureAnalyzer
		MethodDeclaration methodDeclaration= locateSelectedMethod();
		JDTIdentityMapper mapper= new JDTIdentityMapper(JavaSourceAnalysisScope.SOURCE, fAST);
		MethodReference methodRef= mapper.getMethodRef(methodDeclaration.resolveBinding());
		final IMethod resolvedMethod= classHierarchy.resolveMethod(methodRef);
		IR ir= cache.getSSACache().findOrCreateIR(resolvedMethod, Everywhere.EVERYWHERE, options.getSSAOptions());
		ProgramDependenceGraph pdg= ProgramDependenceGraph.makeWithSourceCode(ir, classHierarchy, fDoc);

		for (int stageNumber= 0; stageNumber < stages.keySet().size(); stageNumber++) {
			Stage stage= stages.get(stageNumber);
			stage.setPdgAnalyzer(new PDGExtractClosureAnalyzer(pdg, fDoc, stage.getStage().getStageLines()));
		}
	}

	/**
	 * Returns the parameter infos.
	 * 
	 * @return a list of parameter infos.
	 */
	public List<ParameterInfo> getParameterInfos() {
		return fParameterInfos;
	}

	public ICompilationUnit getCompilationUnit() {
		return fCUnit;
	}

	/**
	 * Checks if varargs are ordered correctly.
	 * 
	 * @return validation status
	 */
	public RefactoringStatus checkVarargOrder() {
		for (Iterator<ParameterInfo> iter= fParameterInfos.iterator(); iter.hasNext();) {
			ParameterInfo info= iter.next();
			if (info.isOldVarargs() && iter.hasNext()) {
				return RefactoringStatus.createFatalErrorStatus(Messages.format(
						JFlowRefactoringCoreMessages.ExtractClosureRefactoring_error_vararg_ordering,
						BasicElementLabels.getJavaElementName(info.getOldName())));
			}
		}
		return new RefactoringStatus();
	}

	/* (non-Javadoc)
	 * Method declared in Refactoring
	 */
	@Override
	public RefactoringStatus checkFinalConditions(IProgressMonitor pm) throws CoreException {
		pm.subTask(EMPTY);

		RefactoringStatus result= new RefactoringStatus();
		result.merge(checkVarargOrder());
		pm.worked(1);
		if (pm.isCanceled())
			throw new OperationCanceledException();

		pm.done();
		return result;
	}

	/* (non-Javadoc)
	 * Method declared in IRefactoring
	 */
	@Override
	public Change createChange(IProgressMonitor pm) throws CoreException {
		pm.beginTask("", 2); //$NON-NLS-1$

		return new CompilationUnitChange(JFlowRefactoringCoreMessages.ExtractClosureRefactoring_change_name, fCUnit);
//		try {
//			BodyDeclaration declaration= fAnalyzer.getEnclosingBodyDeclaration();
//			fRewriter= ASTRewrite.create(declaration.getAST());
//
//			final CompilationUnitChange result= new CompilationUnitChange(JFlowRefactoringCoreMessages.ExtractClosureRefactoring_change_name, fCUnit);
//			result.setSaveMode(TextFileChange.KEEP_SAVE_STATE);
//
//			MultiTextEdit root= new MultiTextEdit();
//			result.setEdit(root);
//
//			ASTNode[] selectedNodes= fAnalyzer.getSelectedNodes();
//
//			TextEditGroup closureEditGroup= new TextEditGroup("Extract to Closure");
//			result.addTextEditGroup(closureEditGroup);
//
//			// A sentinel is just a placeholder to keep track of the position of insertion
//			// For this refactoring, we need to insert two things:
//			// 1) The DataflowChannels (if necessary)
//			// 2) The DataflowMessagingRunnable
//			Block sentinel= fAST.newBlock();
//			ListRewrite sentinelRewriter= fRewriter.getListRewrite(selectedNodes[0].getParent(), (ChildListPropertyDescriptor)selectedNodes[0].getLocationInParent());
//			sentinelRewriter.insertBefore(sentinel, selectedNodes[0], null);
//
//			// Add the dataflowChannels that are required
//			addDataflowChannels(declaration, sentinel, sentinelRewriter, closureEditGroup, pm);
//
//			// Update all references to values written in the closure body to read from channels
//			List<ASTNode> channels= createTempVariablesForChannels(declaration, closureEditGroup, pm);
//			for (ASTNode astNode : channels) {
//				sentinelRewriter.insertAfter(astNode, sentinel, closureEditGroup);
//			}
//
//			// Handle InterruptedException from using DataflowChannels
//			updateExceptions(declaration, closureEditGroup);
//
//			// Replace the placeholder sentinel with the actual code
//			ExpressionStatement closureInvocationStatement= createClosureInvocationStatement(declaration, selectedNodes, closureEditGroup, pm);
//			sentinelRewriter.replace(sentinel, closureInvocationStatement, closureEditGroup);
//
//			if (fImportRewriter.hasRecordedChanges()) {
//				TextEdit edit= fImportRewriter.rewriteImports(null);
//				root.addChild(edit);
//				result.addTextEditGroup(new TextEditGroup(JFlowRefactoringCoreMessages.ExtractClosureRefactoring_organize_imports, new TextEdit[] { edit }));
//			}
//			root.addChild(fRewriter.rewriteAST());
//			return result;
//		} finally {
//			pm.done();
//		}
	}

	private ExpressionStatement createClosureInvocationStatement(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, ASTNode[] selectedNodes,
			TextEditGroup closureEditGroup,
			IProgressMonitor pm) throws JavaModelException {
		ClassInstanceCreation dataflowClosure= createNewDataflowClosure(pdgAnalyzer, analyzer, declaration, selectedNodes, fCUnit.findRecommendedLineSeparator(), closureEditGroup, pm);
		MethodInvocation closureInvocation= createClosureInvocation(dataflowClosure);
		ExpressionStatement closureInvocationStatement= fAST.newExpressionStatement(closureInvocation);
		return closureInvocationStatement;
	}

	private void updateExceptions(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, TextEditGroup closureEditGroup) {
		// If there was indeed a read using getVal on a DataflowChannel, then there is a potential exception
		if (pdgAnalyzer.getOutputBindings(analyzer.getSelectedNodes()).size() != 0) {
			MethodDeclaration method= (MethodDeclaration)declaration;
			// This is safe since MethodDeclaration THROWN_EXCEPTIONS_PROPERTY returns a list of Name
			@SuppressWarnings("unchecked")
			List<Name> exceptions= (List<Name>)fRewriter.getListRewrite(method, MethodDeclaration.THROWN_EXCEPTIONS_PROPERTY).getOriginalList();
			for (Name name : exceptions) {
				if (name.getFullyQualifiedName().matches("InterruptedException"))
					return;
			}
			Name exception= ASTNodeFactory.newName(fAST, "InterruptedException");
			fRewriter.getListRewrite(method, MethodDeclaration.THROWN_EXCEPTIONS_PROPERTY).insertLast(exception, closureEditGroup);
		}
	}

	private List<ASTNode> createTempVariablesForChannels(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, TextEditGroup closureEditGroup,
			IProgressMonitor pm) {
		List<ASTNode> nodes= new ArrayList<ASTNode>();
		int channelNumber= generateFreshChannelNumber(declaration, pm);
		for (IVariableBinding potentialReads : pdgAnalyzer.getOutputBindings(analyzer.getSelectedNodes())) {
			VariableDeclarationStatement tempVariable= createDeclaration(analyzer, potentialReads, createChannelRead(channelNumber));
			nodes.add(tempVariable);
			channelNumber++;
		}

		return nodes;
	}

	private Expression createChannelRead(int channelNumber) {
		MethodInvocation methodInvocation= fAST.newMethodInvocation();
		methodInvocation.setExpression(fAST.newSimpleName(GENERIC_CHANNEL_NAME + channelNumber));
		methodInvocation.setName(fAST.newSimpleName("getVal"));
		return methodInvocation;
	}

	private void addDataflowChannels(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, Block sentinel, ListRewrite sentinelRewriter,
			TextEditGroup closureEditGroup, IProgressMonitor pm) {
		int channelNumber= generateFreshChannelNumber(declaration, pm);
		if (analyzer.getPotentialReadsOutsideOfClosure().length != 0) {
			for (IVariableBinding potentialReads : pdgAnalyzer.getOutputBindings(analyzer.getSelectedNodes())) {
				// Use string generation since this is a single statement
				String channel= "final DataflowQueue<" + resolveType(potentialReads) + "> " + GENERIC_CHANNEL_NAME + channelNumber + "= new DataflowQueue<" + resolveType(potentialReads) + ">();";
				ASTNode newStatement= ASTNodeFactory.newStatement(fAST, channel);
				sentinelRewriter.insertBefore(newStatement, sentinel, closureEditGroup);
				channelNumber++;
			}

			fImportRewriter.addImport(DATAFLOWQUEUE_TYPE);
		}
	}


	private int generateFreshChannelNumber(BodyDeclaration declaration, IProgressMonitor pm) {
		DataflowChannelVisitor channelNameCollector= new DataflowChannelVisitor(pm);
		declaration.accept(channelNameCollector);
		return channelNameCollector.getLargestChannelNumber() + 1;
	}


	// TODO: Is there a utility class that does this mapping?
	final static Map<String, String> primitivesToClass= new HashMap<String, String>();
	static {
		primitivesToClass.put("char", "Character");
		primitivesToClass.put("byte", "Byte");
		primitivesToClass.put("short", "Short");
		primitivesToClass.put("int", "Integer");
		primitivesToClass.put("long", "Long");
		primitivesToClass.put("float", "Float");
		primitivesToClass.put("double", "Double");
		primitivesToClass.put("boolean", "Boolean");
	}

	private String resolveType(IVariableBinding binding) {
		ITypeBinding type= binding.getType();
		if (type.isPrimitive())
			return primitivesToClass.get(type.getName());
		return type.getName();
	}

	private VariableDeclaration getVariableDeclaration(ExtractClosureAnalyzer analyzer, ParameterInfo parameter) {
		return ASTNodes.findVariableDeclaration(parameter.getOldBinding(), analyzer.getEnclosingBodyDeclaration());
	}

	private VariableDeclarationStatement createDeclaration(ExtractClosureAnalyzer analyzer, IVariableBinding binding, Expression intilizer) {
		VariableDeclaration original= ASTNodes.findVariableDeclaration(binding, analyzer.getEnclosingBodyDeclaration());
		VariableDeclarationFragment fragment= fAST.newVariableDeclarationFragment();
		fragment.setName((SimpleName)ASTNode.copySubtree(fAST, original.getName()));
		fragment.setInitializer(intilizer);
		VariableDeclarationStatement result= fAST.newVariableDeclarationStatement(fragment);
		// This is safe because we are dealing exclusively with ASTNode
		@SuppressWarnings("unchecked")
		List<ASTNode> copySubtrees= (List<ASTNode>)ASTNode.copySubtrees(fAST, ASTNodes.getModifiers(original));
		@SuppressWarnings("unchecked")
		List<ASTNode> modifiers= (List<ASTNode>)result.modifiers();
		modifiers.addAll(copySubtrees);
		result.setType(ASTNodeFactory.newType(fAST, original, fImportRewriter, new ContextSensitiveImportRewriteContext(original, fImportRewriter)));
		return result;
	}

	private MethodInvocation createClosureInvocation(ClassInstanceCreation dataflowClosure) {
		MethodInvocation closureInvocation= fAST.newMethodInvocation();
		closureInvocation.setName(fAST.newSimpleName(CLOSURE_INVOCATION_METHOD_NAME));
		closureInvocation.setExpression(dataflowClosure);
		createClosureArguments(closureInvocation);
		return closureInvocation;
	}

	private void createClosureArguments(MethodInvocation closureInvocation) {
		// This is safe since MethodInvocation.arguments() returns a list of Expression
		@SuppressWarnings("unchecked")
		List<Expression> arguments= (List<Expression>)closureInvocation.arguments();
		for (int i= 0; i < fParameterInfos.size(); i++) {
			ParameterInfo parameter= fParameterInfos.get(i);
			arguments.add(ASTNodeFactory.newName(fAST, parameter.getOldName()));
		}
	}

	/**
	 * Create an ASTNode similar to
	 * 
	 * new DataFlowMessagingRunnable(...){...}
	 * 
	 * @param selectedNodes
	 * @param findRecommendedLineSeparator
	 * @param editGroup
	 * @return
	 */
	private ClassInstanceCreation createNewDataflowClosure(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, ASTNode[] selectedNodes,
			String findRecommendedLineSeparator,
			TextEditGroup editGroup, IProgressMonitor pm) {
		ClassInstanceCreation dataflowClosure= fAST.newClassInstanceCreation();

		// Create the small chunks
		augmentWithTypeInfo(analyzer, dataflowClosure);
		augmentWithConstructorArgument(dataflowClosure);
		augmentWithAnonymousClassDeclaration(pdgAnalyzer, analyzer, declaration, dataflowClosure, selectedNodes, editGroup, pm);

		return dataflowClosure;
	}

	private void augmentWithTypeInfo(ExtractClosureAnalyzer analyzer, ClassInstanceCreation dataflowClosure) {
		ImportRewriteContext context= new ContextSensitiveImportRewriteContext(analyzer.getEnclosingBodyDeclaration(), fImportRewriter);
		fImportRewriter.addImport(CLOSURE_QUALIFIED_TYPE, context);
		dataflowClosure.setType(fAST.newSimpleType(fAST.newName(CLOSURE_TYPE)));
	}

	private void augmentWithConstructorArgument(ClassInstanceCreation dataflowClosure) {
		String argumentsCount= new Integer(fParameterInfos.size()).toString();
		// This is safe since ClassInstanceCreation.arguments() can only return Expression
		@SuppressWarnings("unchecked")
		List<Expression> arguments= (List<Expression>)dataflowClosure.arguments();
		arguments.add(fAST.newNumberLiteral(argumentsCount));
	}

	private void augmentWithAnonymousClassDeclaration(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, ClassInstanceCreation dataflowClosure,
			ASTNode[] selectedNodes,
			TextEditGroup editGroup, IProgressMonitor pm) {
		AnonymousClassDeclaration closure= fAST.newAnonymousClassDeclaration();
		MethodDeclaration createRunMethodForClosure= createRunMethodForClosure(pdgAnalyzer, analyzer, declaration, selectedNodes, editGroup, pm);
		// This is safe since AnonymousClassDeclaration.bodyDeclarations can only return a list of BodyDeclaration
		@SuppressWarnings("unchecked")
		List<BodyDeclaration> bodyDeclarations= (List<BodyDeclaration>)closure.bodyDeclarations();
		bodyDeclarations.add(createRunMethodForClosure);
		dataflowClosure.setAnonymousClassDeclaration(closure);
	}

	/**
	 * Create a ASTNode similar to
	 * 
	 * protected void doRun(Object... arguments) { ... }
	 * 
	 * @param selectedNodes - The statements to be enclosed in the doRun(...) method
	 * @param editGroup
	 * @return
	 */
	private MethodDeclaration createRunMethodForClosure(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, ASTNode[] selectedNodes,
			TextEditGroup editGroup, IProgressMonitor pm) {
		MethodDeclaration runMethod= fAST.newMethodDeclaration();
		List<Modifier> newModifiers= ASTNodeFactory.newModifiers(fAST, Modifier.PROTECTED);

		// This is safe since MethodDeclaration.modifiers() returns a list of IExtendedModifier
		@SuppressWarnings("unchecked")
		List<IExtendedModifier> modifiers= (List<IExtendedModifier>)runMethod.modifiers();
		modifiers.addAll(newModifiers);
		runMethod.setReturnType2(fAST.newPrimitiveType(org.eclipse.jdt.core.dom.PrimitiveType.VOID));
		runMethod.setName(fAST.newSimpleName(CLOSURE_METHOD));

		// This is safe since MethodDeclaration.paramters() returns a list of SingleVariableDeclaration
		@SuppressWarnings("unchecked")
		List<SingleVariableDeclaration> parameters= (List<SingleVariableDeclaration>)runMethod.parameters();
		parameters.add(createObjectArrayArgument());
		runMethod.setBody(createClosureBody(pdgAnalyzer, analyzer, declaration, selectedNodes, editGroup, pm));

		return runMethod;
	}

	/**
	 * Creates an the Object... arguments type
	 * 
	 * @return
	 */
	private SingleVariableDeclaration createObjectArrayArgument() {
		SingleVariableDeclaration parameter= fAST.newSingleVariableDeclaration();
		parameter.setVarargs(true);
		parameter.setType(fAST.newSimpleType(fAST.newSimpleName(CLOSURE_PARAMETER_TYPE)));
		parameter.setName(fAST.newSimpleName(CLOSURE_PARAMETER_NAME));
		return parameter;
	}

	private Block createClosureBody(PDGExtractClosureAnalyzer pdgAnalyzer, ExtractClosureAnalyzer analyzer, BodyDeclaration declaration, ASTNode[] selectedNodes, TextEditGroup editGroup,
			IProgressMonitor pm) {
		Block methodBlock= fAST.newBlock();
		ListRewrite statements= fRewriter.getListRewrite(methodBlock, Block.STATEMENTS_PROPERTY);

		// Locals that are not passed as an arguments since the extracted method only
		// writes to them
		List<IVariableBinding> unfilteredMethodLocals= pdgAnalyzer.getLocalVariableBindings(analyzer.getSelectedNodes());
		List<IVariableBinding> methodLocals= removeSelectedDeclarations(analyzer, unfilteredMethodLocals);
		for (IVariableBinding binding : methodLocals) {
			@SuppressWarnings("unchecked")
			List<Statement> methodBlockStatements= (List<Statement>)methodBlock.statements();
			methodBlockStatements.add(createDeclaration(analyzer, binding, null));
		}

		// Update the bindings to the parameters
		int argumentPosition= 0;
		for (ParameterInfo parameter : fParameterInfos) {
			for (int n= 0; n < selectedNodes.length; n++) {
				SimpleName[] oldNames= LinkedNodeFinder.findByBinding(selectedNodes[n], parameter.getOldBinding());
				for (int i= 0; i < oldNames.length; i++) {
					fRewriter.replace(oldNames[i], createCastParameters(analyzer, parameter, argumentPosition), null);
				}
			}
			argumentPosition++;
		}

		ListRewrite source= fRewriter.getListRewrite(selectedNodes[0].getParent(), (ChildListPropertyDescriptor)selectedNodes[0].getLocationInParent());
		ASTNode toMove= source.createMoveTarget(selectedNodes[0], selectedNodes[selectedNodes.length - 1], null, editGroup);
		statements.insertLast(toMove, editGroup);

		// Add the potential writes at the end (in case multiple writes have occurred and we only want the latest values)  
		int channelNumber= generateFreshChannelNumber(declaration, pm);
		for (IVariableBinding potentialWrites : pdgAnalyzer.getOutputBindings(analyzer.getSelectedNodes())) {
			// Use string generation since this is a single statement
			String channel= GENERIC_CHANNEL_NAME + channelNumber + "." + DATAFLOWQUEUE_PUT_METHOD + "(" + potentialWrites.getName() + ");";
			ASTNode newStatement= ASTNodeFactory.newStatement(fAST, channel);
			statements.insertLast(newStatement, editGroup);
			channelNumber++;
		}


		return methodBlock;
	}

	private List<IVariableBinding> removeSelectedDeclarations(ExtractClosureAnalyzer analyzer, List<IVariableBinding> unfilteredMethodLocals) {
		List<IVariableBinding> result= new ArrayList<IVariableBinding>();
		Selection selection= analyzer.getSelection();
		for (IVariableBinding binding : unfilteredMethodLocals) {
			ASTNode decl= ((CompilationUnit)analyzer.getEnclosingBodyDeclaration().getRoot()).findDeclaringNode(binding);
			if (!selection.covers(decl))
				result.add(binding);
		}
		return result;
	}

	private ASTNode createCastParameters(ExtractClosureAnalyzer analyzer, ParameterInfo parameter, int argumentsPosition) {
		ParenthesizedExpression argumentExpression= fAST.newParenthesizedExpression();
		CastExpression castExpression= fAST.newCastExpression();

		IVariableBinding oldBinding= parameter.getOldBinding();
		if (oldBinding.getType().isPrimitive()) {
			castExpression.setType(fAST.newSimpleType(fAST.newName(resolveType(oldBinding))));
		} else {
			VariableDeclaration infoDecl= getVariableDeclaration(analyzer, parameter);
			castExpression.setType(ASTNodeFactory.newType(fAST, infoDecl, fImportRewriter, null));
		}

		ArrayAccess arrayAccess= fAST.newArrayAccess();
		arrayAccess.setArray(fAST.newSimpleName(CLOSURE_PARAMETER_NAME));
		arrayAccess.setIndex(fAST.newNumberLiteral(Integer.toString(argumentsPosition)));
		castExpression.setExpression(arrayAccess);

		argumentExpression.setExpression(castExpression);
		return argumentExpression;
	}



}
