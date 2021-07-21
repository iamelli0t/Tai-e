package pascal.taie.analysis.pta.plugin.exception;

import pascal.taie.World;
import pascal.taie.analysis.exception.CatchAnalysis;
import pascal.taie.analysis.exception.PTABasedThrowResult;
import pascal.taie.analysis.graph.callgraph.CallGraph;
import pascal.taie.analysis.graph.callgraph.Edge;
import pascal.taie.analysis.pta.core.cs.context.Context;
import pascal.taie.analysis.pta.core.cs.element.CSCallSite;
import pascal.taie.analysis.pta.core.cs.element.CSManager;
import pascal.taie.analysis.pta.core.cs.element.CSMethod;
import pascal.taie.analysis.pta.core.cs.element.CSObj;
import pascal.taie.analysis.pta.core.cs.element.CSVar;
import pascal.taie.analysis.pta.core.heap.Obj;
import pascal.taie.analysis.pta.core.solver.Solver;
import pascal.taie.analysis.pta.plugin.Plugin;
import pascal.taie.analysis.pta.pts.PointsToSet;
import pascal.taie.ir.IR;
import pascal.taie.ir.exp.Var;
import pascal.taie.ir.proginfo.ExceptionEntry;
import pascal.taie.ir.stmt.Catch;
import pascal.taie.ir.stmt.Invoke;
import pascal.taie.ir.stmt.Stmt;
import pascal.taie.ir.stmt.Throw;
import pascal.taie.language.classes.JMethod;
import pascal.taie.language.type.Type;
import pascal.taie.language.type.TypeManager;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static pascal.taie.util.collection.MapUtils.newHybridMap;
import static pascal.taie.util.collection.SetUtils.newHybridSet;


public class ExceptionHandler implements Plugin {

    private Solver pta;

    private final Map<CSMethod, CSMethodExceptionResult> csMethodResultMap;

    private final Map<Stmt, Collection<ExceptionEntry>> stmtToExceptionEntries;

    private final Map<Var, Collection<Throw>> varToStmt;

    private PTABasedThrowResult ptaBasedThrowResult;

    private final ExceptionWorkList workList;

    public ExceptionHandler() {
        csMethodResultMap = newHybridMap();
        varToStmt = newHybridMap();
        workList = new ExceptionWorkList();
        stmtToExceptionEntries = newHybridMap();
    }

    public Map<CSMethod, CSMethodExceptionResult> getCSMethodResultMap() {
        return csMethodResultMap;
    }


    public Map<Var, Collection<Throw>> getVarToStmt() {
        return varToStmt;
    }

    @Override
    public void setSolver(Solver pta) {
        this.pta = pta;
        this.ptaBasedThrowResult = pta.getPTABasedThrowResult();
    }

    @Override
    public void onFinish() {
        csMethodResultMap.forEach((csMethod, csMethodExceptionResult) -> {
            JMethod method = csMethod.getMethod();
            JMethodExceptionResult jMethodExceptionResult =
                    ptaBasedThrowResult.getExceptionResult(method);
            jMethodExceptionResult.
                    addCSMethodExceptionResult(csMethodExceptionResult);

            if (method.getDeclaringClass().isApplication()) {
                System.out.println(jMethodExceptionResult);
            }
        });
    }

    @Override
    public void onNewPointsToSet(CSVar csVar, PointsToSet pts) {
        Var exceptionRef = csVar.getVar();
        Collection<Throw> throwStmtSet = varToStmt.get(exceptionRef);

        if (throwStmtSet != null) {
            CSManager csManager = pta.getCSManager();
            Context ctx = csVar.getContext();
            JMethod currentMethod = exceptionRef.getMethod();
            CSMethod currentCSMethod = csManager.getCSMethod(ctx, currentMethod);

            Stream<CSObj> allObjs = pts.objects();
            Collection<CSObj> exceptions = newHybridSet();
            allObjs.forEach(exceptions::add);

            throwStmtSet.forEach(throwStmt -> {
                workList.addExceptionEntry(currentCSMethod, throwStmt, exceptions);
                exceptionPropagate();
            });
        }
    }

    @Override
    public void onNewCallEdge(Edge<CSCallSite, CSMethod> edge) {
        Invoke invoke = edge.getCallSite()
                .getCallSite();
        CSMethod callerCSMethod = edge.getCallSite().getContainer();
        CSMethod calleeCSMethod = edge.getCallee();
        CSMethodExceptionResult CSMethodExceptionResult =
                csMethodResultMap.computeIfAbsent(calleeCSMethod,
                        (key) -> new CSMethodExceptionResult());
        Collection<CSObj> exceptions = CSMethodExceptionResult.
                getThrownExplicitExceptions();
        if (exceptions.size() > 0) {
            workList.addExceptionEntry(callerCSMethod, invoke, exceptions);
            exceptionPropagate();
        }
    }

    @Override
    public void onNewMethod(JMethod method) {
        if (method.getDeclaringClass().isApplication()) {
            System.out.println(method.toString());
        }
        IR ir = method.getIR();
        ir.getStmts().forEach(stmt -> {
            if (stmt instanceof Throw) {
                Throw throwStmt = (Throw) stmt;
                Var exceptionRef = throwStmt.getExceptionRef();
                Collection<Throw> throwStmtSet = varToStmt.
                        getOrDefault(exceptionRef, newHybridSet());
                throwStmtSet.add(throwStmt);
                varToStmt.putIfAbsent(exceptionRef, throwStmtSet);

                if (method.getDeclaringClass().isApplication()) {
                    System.out.print("exceptionRef:" + exceptionRef + "    ");
                    System.out.println("throwStmtSet:" + throwStmtSet);
                }
            }
        });
    }

    private void exceptionPropagate() {
        while (!workList.isEmpty()) {
            ExceptionWorkList.Entry entry = workList.pollPointerEntry();
            CSMethod csMethod = entry.csMethod;
            Stmt stmt = entry.stmt;
            Collection<CSObj> exceptions = entry.exceptions;
            CSMethodExceptionResult CSMethodExceptionResult
                    = csMethodResultMap.computeIfAbsent(csMethod,
                    (key) -> new CSMethodExceptionResult());
            exceptions = CSMethodExceptionResult.
                    getDifferentExceptions(stmt, exceptions);
            if (exceptions.size() > 0) {
                if (csMethod.getMethod().getDeclaringClass().isApplication()) {
                    System.out.println("workList process " + csMethod);
                    System.out.println("-------stmt " + stmt);
                    System.out.println("-------exceptions" + exceptions);
                }
                Collection<CSObj> uncaughtExceptions = IntraprocedualExceptionCaught(
                        CSMethodExceptionResult,
                        stmt,
                        exceptions,
                        csMethod
                );
                if (uncaughtExceptions.size() > 0) {
                    CallGraph<CSCallSite, CSMethod> callGraph = pta.getCallGraph();
                    Stream<CSCallSite> callers = callGraph.callersOf(csMethod);
                    callers.forEach(csCallSite -> {
                        Stmt invoke = csCallSite.getCallSite();
                        CSMethod callerMethod = csCallSite.getContainer();
                        workList.addExceptionEntry(
                                callerMethod,
                                invoke,
                                uncaughtExceptions);
                    });
                }
            }
        }
    }

    private Collection<CSObj> IntraprocedualExceptionCaught(
            CSMethodExceptionResult CSMethodExceptionResult,
            Stmt currentStmt,
            Collection<CSObj> newExceptions,
            CSMethod csMethod) {
        CSMethodExceptionResult.addExplicit(currentStmt, newExceptions);
        JMethod method = csMethod.getMethod();
        Context ctx = csMethod.getContext();
        IR ir = method.getIR();
        List<ExceptionEntry> exceptionEntries = (List<ExceptionEntry>)
                stmtToExceptionEntries.computeIfAbsent(currentStmt, (key) ->
                        CatchAnalysis.getPotentialCatchers(ir).get(currentStmt)
                );
        TypeManager typeManager = World.getTypeManager();
        if (exceptionEntries != null) {
            for (ExceptionEntry exceptionEntry : exceptionEntries) {
                Collection<CSObj> uncaughtExceptions = newHybridSet();
                newExceptions.forEach(newException -> {
                    Context heapCtx = newException.getContext();
                    Obj exceptionObj = newException.getObject();
                    Type t = exceptionObj.getType();
                    if (typeManager.isSubtype(exceptionEntry.getCatchType(), t)) {
                        Catch catchStmt = exceptionEntry.getHandler();
                        Var exceptionRef = catchStmt.getExceptionRef();

                        if (method.getDeclaringClass().isApplication()) {
                            System.out.print("context: " + ctx);
                            System.out.println("exceptionRef:" + exceptionRef);
                            System.out.println("--------exception:" + exceptionObj);
                            System.out.println("--------stmt:" + catchStmt);
                        }
                        pta.addVarPointsTo(ctx, exceptionRef, heapCtx, exceptionObj);
                    } else {
                        uncaughtExceptions.add(newException);
                    }
                });
                newExceptions = uncaughtExceptions;
            }
        }
        CSMethodExceptionResult.addUncaughtExceptions(newExceptions);
        return newExceptions;
    }
}