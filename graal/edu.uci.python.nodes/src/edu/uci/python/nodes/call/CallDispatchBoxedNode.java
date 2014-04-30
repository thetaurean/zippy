/*
 * Copyright (c) 2014, Regents of the University of California
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package edu.uci.python.nodes.call;

import org.python.core.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

import edu.uci.python.nodes.*;
import edu.uci.python.nodes.frame.*;
import edu.uci.python.nodes.object.*;
import edu.uci.python.runtime.*;
import edu.uci.python.runtime.builtin.*;
import edu.uci.python.runtime.function.*;
import edu.uci.python.runtime.object.*;
import edu.uci.python.runtime.standardtype.*;
import static edu.uci.python.nodes.call.PythonCallUtil.*;

public abstract class CallDispatchBoxedNode extends CallDispatchNode {

    public CallDispatchBoxedNode(String calleeName) {
        super(calleeName);
    }

    protected abstract Object executeCall(VirtualFrame frame, PythonObject primaryObj, Object[] arguments, PKeyword[] keywords);

    protected final Object executeCallAndRewrite(CallDispatchBoxedNode next, VirtualFrame frame, PythonObject primaryObj, Object[] arguments, PKeyword[] keywords) {
        CompilerDirectives.transferToInterpreterAndInvalidate();
        return replace(next).executeCall(frame, primaryObj, arguments, keywords);
    }

    protected static CallDispatchBoxedNode create(PythonContext context, PythonObject primary, String calleeName, PythonCallable callee, PNode calleeNode, PKeyword[] keywords) {
        UninitializedDispatchBoxedNode next = new UninitializedDispatchBoxedNode(context, callee.getName(), calleeNode, keywords.length != 0);

        if (isNotBuiltin(callee)) {
            ShapeCheckNode check = null;

            if (primary instanceof PythonModule) {
                check = createCheckNodeForPythonModule(context, (PythonModule) primary, calleeNode, calleeName);
            } else if (primary.isOwnAttribute(calleeName)) {
                check = ShapeCheckNode.create(primary, primary.getObjectLayout(), 0);
            } else {
                // class chain lookup
                int depth = 1;
                PythonClass current = primary.getPythonClass();
                do {
                    if (current.isOwnAttribute(calleeName)) {
                        break;
                    }

                    current = current.getSuperClass();
                    depth++;
                } while (current != null);

                if (current == null) {
                    throw Py.AttributeError(primary + " object has no attribute " + calleeName);
                }

                check = ShapeCheckNode.create(primary, current.getObjectLayout(), depth);
            }

            assert check != null;

            /**
             * Treat generator as slow path for now.
             */
            if (callee instanceof PGeneratorFunction) {
                return new GenericDispatchBoxedNode(callee.getName(), calleeNode);
            }

            if (callee instanceof PFunction || callee instanceof PMethod) {
                return new DispatchFunctionNode(callee, check, next);
            } else if (callee instanceof PythonClass) {
                PythonClass clazz = (PythonClass) callee;
                PythonCallable ctor = clazz.lookUpMethod("__init__");
                if (ctor instanceof PFunction) {
                    return new DispatchFunctionNode(ctor, check, next);
                } else if (ctor instanceof PBuiltinFunction) {
                    return new DispatchBuiltinNode(ctor, check, next);
                }
            }
        } else if (isBuiltin(callee)) {
            ShapeCheckNode check = null;

            if (primary instanceof PythonModule) {
                check = createCheckNodeForPythonModule(context, (PythonModule) primary, calleeNode, calleeName);
            } else if (primary instanceof PythonBuiltinClass) {
                /**
                 * Since built-in classes are immutable, there is no need to probe the exact storage
                 * object.
                 */
                check = ShapeCheckNode.create(primary, primary.getObjectLayout(), 0);
            }

            assert check != null;
            return new DispatchBuiltinNode(callee, check, next);
        }

        throw new UnsupportedOperationException("Unsupported callee type " + callee);
    }

    private static ShapeCheckNode createCheckNodeForPythonModule(PythonContext context, PythonModule primary, PNode calleeNode, String calleeName) {
        if (primary.isOwnAttribute(calleeName)) {
            return ShapeCheckNode.create(primary, primary.getObjectLayout(), 0);
        } else if (calleeNode instanceof ReadGlobalNode) {
            PythonModule builtinsModule = context.getBuiltins();

            if (builtinsModule.isOwnAttribute(calleeName)) {
                return ShapeCheckNode.create(primary, builtinsModule.getObjectLayout(), 1);
            }
        }

        throw new UnsupportedOperationException();
    }

    /**
     * The primary could be:
     * <p>
     * 1. The global {@link PythonModule}. <br>
     * 3. A {@link PythonModule}. <br>
     * 2. A {@link PythonClass}. <br>
     * 4. A {@link PythonObject}
     *
     */
    public static final class DispatchFunctionNode extends CallDispatchBoxedNode {

        @Child protected ShapeCheckNode check;
        @Child protected InvokeNode invoke;
        @Child protected CallDispatchBoxedNode next;

        public DispatchFunctionNode(PythonCallable callee, ShapeCheckNode check, UninitializedDispatchBoxedNode next) {
            super(callee.getName());
            this.check = check;
            this.next = next;
            this.invoke = InvokeNode.create(callee, next.hasKeyword);
            assert callee instanceof PFunction || callee instanceof PMethod;
        }

        @Override
        protected Object executeCall(VirtualFrame frame, PythonObject primaryObj, Object[] arguments, PKeyword[] keywords) {
            try {
                if (check.accept(primaryObj)) {
                    return invoke.invoke(frame, primaryObj, arguments, keywords);
                } else {
                    return next.executeCall(frame, primaryObj, arguments, keywords);
                }
            } catch (InvalidAssumptionException ex) {
                return executeCallAndRewrite(next, frame, primaryObj, arguments, keywords);
            }
        }
    }

    /**
     * The primary could be:
     * <p>
     * 1. The global {@link PythonModule}. <br>
     * 2. A built-in {@link PythonModule}. <br>
     * 3. A built-in {@link PythonBuiltinClass}.
     *
     */
    public static final class DispatchBuiltinNode extends CallDispatchBoxedNode {

        @Child protected ShapeCheckNode check;
        @Child protected InvokeNode invoke;
        @Child protected CallDispatchBoxedNode next;

        public DispatchBuiltinNode(PythonCallable callee, ShapeCheckNode check, UninitializedDispatchBoxedNode next) {
            super(callee.getName());
            assert callee instanceof PBuiltinFunction || callee instanceof PythonBuiltinClass;

            if (callee instanceof PythonBuiltinClass) {
                this.invoke = InvokeNode.create(((PythonBuiltinClass) callee).lookUpMethod("__init__"), next.hasKeyword);
            } else {
                this.invoke = InvokeNode.create(callee, next.hasKeyword);
            }

            this.check = check;
            this.next = next;
        }

        @Override
        protected Object executeCall(VirtualFrame frame, PythonObject primaryObj, Object[] arguments, PKeyword[] keywords) {
            try {
                if (check.accept(primaryObj)) {
                    return invoke.invoke(frame, primaryObj, arguments, keywords);
                } else {
                    return next.executeCall(frame, primaryObj, arguments, keywords);
                }
            } catch (InvalidAssumptionException ex) {
                return executeCallAndRewrite(next, frame, primaryObj, arguments, keywords);
            }
        }
    }

    @NodeInfo(cost = NodeCost.MEGAMORPHIC)
    public static final class GenericDispatchBoxedNode extends CallDispatchBoxedNode {

        @Child protected PNode calleeNode;

        public GenericDispatchBoxedNode(String calleeName, PNode calleeNode) {
            super(calleeName);
            this.calleeNode = calleeNode;
        }

        @Override
        protected Object executeCall(VirtualFrame frame, PythonObject primaryObj, Object[] arguments, PKeyword[] keywords) {
            PythonCallable callee;

            try {
                callee = calleeNode.executePythonCallable(frame);
            } catch (UnexpectedResultException e) {
                throw new IllegalStateException("Call to " + e.getMessage() + " not supported.");
            }

            return callee.call(frame.pack(), arguments);
        }
    }

    @NodeInfo(cost = NodeCost.UNINITIALIZED)
    public static final class UninitializedDispatchBoxedNode extends CallDispatchBoxedNode {

        @Child protected PNode calleeNode;
        private final PythonContext context;
        private final boolean hasKeyword;

        public UninitializedDispatchBoxedNode(PythonContext context, String calleeName, PNode calleeNode, boolean hasKeyword) {
            super(calleeName);
            this.calleeNode = calleeNode;
            this.context = context;
            this.hasKeyword = hasKeyword;
        }

        @Override
        protected Object executeCall(VirtualFrame frame, PythonObject primaryObj, Object[] arguments, PKeyword[] keywords) {
            CompilerDirectives.transferToInterpreterAndInvalidate();

            CallDispatchNode current = this;
            int depth = 0;
            while (current.getParent() instanceof CallDispatchNode) {
                current = (CallDispatchNode) current.getParent();
                depth++;
            }

            CallDispatchBoxedNode specialized;
            if (depth < PythonOptions.CallSiteInlineCacheMaxDepth) {
                PythonCallable callee;
                try {
                    callee = calleeNode.executePythonCallable(frame);
                } catch (UnexpectedResultException e) {
                    throw new IllegalStateException("Call to " + e.getMessage() + " not supported.");
                }

                specialized = replace(create(context, primaryObj, calleeName, callee, calleeNode, keywords));
            } else {
                specialized = current.replace(new GenericDispatchBoxedNode(calleeName, calleeNode));
            }

            return specialized.executeCall(frame, primaryObj, arguments, keywords);
        }
    }

}
