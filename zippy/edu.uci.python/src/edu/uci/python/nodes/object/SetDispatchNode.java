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
package edu.uci.python.nodes.object;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

import edu.uci.python.runtime.*;
import edu.uci.python.runtime.object.*;
import edu.uci.python.runtime.object.location.*;

public abstract class SetDispatchNode extends Node {

    protected final String attributeId;

    public SetDispatchNode(String attributeId) {
        this.attributeId = attributeId;
    }

    public abstract void setValue(VirtualFrame frame, PythonObject primary, Object value);

    public void setIntValue(VirtualFrame frame, PythonObject primary, int value) {
        setValue(frame, primary, value);
    }

    public void setDoubleValue(VirtualFrame frame, PythonObject primary, double value) {
        setValue(frame, primary, value);
    }

    public void setBooleanValue(VirtualFrame frame, PythonObject primary, boolean value) {
        setValue(frame, primary, value);
    }

    protected SetDispatchNode rewrite(SetDispatchNode next) {
        CompilerAsserts.neverPartOfCompilation();
        assert this != next;
        return replace(next);
    }

    @NodeInfo(cost = NodeCost.UNINITIALIZED)
    public static final class UninitializedSetDispatchNode extends SetDispatchNode {

        public UninitializedSetDispatchNode(String attributeId) {
            super(attributeId);
        }

        @Override
        public void setValue(VirtualFrame frame, PythonObject primary, Object value) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            assert primary.verifyLayout();

            if (!primary.getStableAssumption().isValid()) {
                primary.syncObjectLayoutWithClass();
            }

            assert primary.verifyLayout();
            SetDispatchNode current = this;
            int depth = 0;

            while (current.getParent() instanceof SetDispatchNode) {
                current = (SetDispatchNode) current.getParent();
                depth++;

                if (!(current instanceof LinkedSetDispatchNode)) {
                    continue;
                }

                /**
                 * After the layout sync, if a previously missed dispatch node caches the updated
                 * layout, we should reuse the existing dispatch node.
                 */
                LinkedSetDispatchNode linked = (LinkedSetDispatchNode) current;
                try {
                    if (linked.check.accept(primary)) {
                        linked.setValue(frame, primary, value);
                        return;
                    }
                } catch (InvalidAssumptionException e) {
                    throw new RuntimeException();
                }
            }

            if (depth < PythonOptions.AttributeAccessInlineCacheMaxDepth) {
                primary.setAttribute(attributeId, value);
                StorageLocation location = primary.getOwnValidLocation(attributeId);
                replace(new LinkedSetDispatchNode(attributeId, AttributeWriteNode.create(location), primary, this));
            } else {
                replace(new GenericSetDispatchNode(attributeId)).setValue(frame, primary, value);
            }
        }
    }

    public static final class GenericSetDispatchNode extends SetDispatchNode {

        public GenericSetDispatchNode(String attributeId) {
            super(attributeId);
        }

        @Override
        public void setValue(VirtualFrame frame, PythonObject primary, Object value) {
            if (!primary.getStableAssumption().isValid()) {
                primary.syncObjectLayoutWithClass();
            }

            primary.setAttribute(attributeId, value);
        }
    }

    public static final class LinkedSetDispatchNode extends SetDispatchNode {

        @Child protected LayoutCheckNode check;
        @Child protected AttributeWriteNode write;
        @Child protected SetDispatchNode next;

        public LinkedSetDispatchNode(String attributeId, AttributeWriteNode write, PythonObject primary, SetDispatchNode next) {
            super(attributeId);
            this.check = LayoutCheckNode.create(primary, attributeId, true);
            this.write = write;
            this.next = next;
        }

        @Override
        public void setValue(VirtualFrame frame, PythonObject primary, Object value) {
            try {
                if (check.accept(primary)) {
                    write.setValueUnsafe(primary, value);
                } else {
                    next.setValue(frame, primary, value);
                }
            } catch (InvalidAssumptionException | StorageLocationGeneralizeException e) {
                rewrite(next).setValue(frame, primary, value);
            }
        }

        @Override
        public void setIntValue(VirtualFrame frame, PythonObject primary, int value) {
            try {
                if (check.accept(primary)) {
                    write.setIntValueUnsafe(primary, value);
                } else {
                    next.setIntValue(frame, primary, value);
                }
            } catch (InvalidAssumptionException | StorageLocationGeneralizeException e) {
                rewrite(next).setValue(frame, primary, value);
            }
        }

        @Override
        public void setDoubleValue(VirtualFrame frame, PythonObject primary, double value) {
            try {
                if (check.accept(primary)) {
                    write.setDoubleValueUnsafe(primary, value);
                } else {
                    next.setDoubleValue(frame, primary, value);
                }
            } catch (InvalidAssumptionException | StorageLocationGeneralizeException e) {
                rewrite(next).setValue(frame, primary, value);
            }
        }

        @Override
        public void setBooleanValue(VirtualFrame frame, PythonObject primary, boolean value) {
            try {
                if (check.accept(primary)) {
                    write.setBooleanValueUnsafe(primary, value);
                } else {
                    next.setBooleanValue(frame, primary, value);
                }
            } catch (InvalidAssumptionException | StorageLocationGeneralizeException e) {
                rewrite(next).setValue(frame, primary, value);
            }
        }
    }

}
