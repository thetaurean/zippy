/*
 * Copyright (c) 2013, Regents of the University of California
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
package edu.uci.python.nodes.access;

import org.python.core.*;

import com.oracle.truffle.api.*;
import com.oracle.truffle.api.CompilerDirectives.SlowPath;
import com.oracle.truffle.api.frame.*;
import com.oracle.truffle.api.nodes.*;

import edu.uci.python.nodes.*;
import edu.uci.python.nodes.literals.*;
import edu.uci.python.nodes.objects.*;
import edu.uci.python.runtime.*;
import edu.uci.python.runtime.datatypes.*;
import edu.uci.python.runtime.standardtypes.*;

public class ReadGlobalScopeNode extends PNode {

    private final String attributeId;

    private final PythonContext context;

    @Child protected LoadAttributeNode load;

    public ReadGlobalScopeNode(PythonContext context, PythonModule globalScope, String attributeId) {
        this.attributeId = attributeId;
        this.context = context;
        PNode primary = new ObjectLiteralNode(globalScope);
        this.load = adoptChild(new UninitializedLoadAttributeNode(attributeId, primary));
    }

    protected ReadGlobalScopeNode(ReadGlobalScopeNode previous) {
        this.attributeId = previous.attributeId;
        this.context = previous.context;
        this.load = previous.load;
    }

    public String getAttributeId() {
        return attributeId;
    }

    @Override
    public int executeInt(VirtualFrame frame) throws UnexpectedResultException {
        return load.executeInt(frame);
    }

    @Override
    public double executeDouble(VirtualFrame frame) throws UnexpectedResultException {
        return load.executeDouble(frame);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        Object value = load.execute(frame);

        if (value == PNone.NONE) {
            value = context.getPythonCore().getBuiltinsModule().getAttribute(attributeId);
        } else {
            return value;
        }

        if (value == PNone.NONE) {
            return slowPathLookup();
        } else {
            cacheBuiltin(value);
            return value;
        }
    }

    @SlowPath
    private Object slowPathLookup() {
        Object value = PySystemState.builtins.__finditem__(attributeId);

        if (value == null) {
            throw Py.NameError("name \'" + attributeId + "\' is not defined");
        }

        return value;
    }

    @SlowPath
    protected void cacheBuiltin(Object builtin) {
        Assumption globalScopeUnchanged = this.context.getPythonCore().getMainModule().getUnmodifiedAssumption();
        Assumption builtinsModuleUnchanged = this.context.getPythonCore().getBuiltinsModule().getUnmodifiedAssumption();
        replace(new ReadBuiltinCachedNode(this, globalScopeUnchanged, builtinsModuleUnchanged, builtin));
    }

    @Override
    public String toString() {
        return "ReadGlobal: " + attributeId;
    }

    public static class ReadBuiltinCachedNode extends ReadGlobalScopeNode {

        private final Assumption globalScopeUnchanged;
        private final Assumption builtinsModuleUnchanged;
        private final Object cachedBuiltin;

        public ReadBuiltinCachedNode(ReadGlobalScopeNode previous, Assumption globalScopeUnchanged, Assumption builtinsModuleUnchanged, Object cachedBuiltin) {
            super(previous);
            this.globalScopeUnchanged = globalScopeUnchanged;
            this.builtinsModuleUnchanged = builtinsModuleUnchanged;
            this.cachedBuiltin = cachedBuiltin;
        }

        @Override
        public Object execute(VirtualFrame frame) {
            try {
                globalScopeUnchanged.check();
                builtinsModuleUnchanged.check();
                return cachedBuiltin;
            } catch (InvalidAssumptionException e) {
                return replace(new ReadGlobalScopeNode(this)).execute(frame);
            }
        }
    }
}
