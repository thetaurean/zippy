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
package edu.uci.python.nodes.optimize;

import java.util.*;

import com.oracle.truffle.api.frame.*;

import edu.uci.python.nodes.*;
import edu.uci.python.nodes.frame.*;
import edu.uci.python.nodes.generator.*;
import edu.uci.python.nodes.generator.ComprehensionNodeFactory.ArrayListAddNodeFactory;
import edu.uci.python.nodes.generator.ComprehensionNodeFactory.TreeSetAddNodeFactory;
import edu.uci.python.runtime.builtin.*;
import edu.uci.python.runtime.function.*;

public enum IntrinsifiableBuiltin {

    LIST("list"),
    TUPLE("tuple"),
    SET("set");

    private final String name;
    private static final Map<String, IntrinsifiableBuiltin> TargetBuiltins = new HashMap<>();

    static {
        TargetBuiltins.put(LIST.name, LIST);
        TargetBuiltins.put(TUPLE.name, TUPLE);
        TargetBuiltins.put(SET.name, SET);
    }

    private IntrinsifiableBuiltin(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public static IntrinsifiableBuiltin findIntrinsifiable(String name) {
        return TargetBuiltins.get(name);
    }

    public static boolean isIntrinsifiable(PythonCallable callee) {
        return callee instanceof PythonBuiltinClass && TargetBuiltins.containsKey(callee.getName());
    }

    public ComprehensionNode createComprehensionNode(FrameSlot targetSlot, PNode comprehension) {
        switch (this) {
            case LIST:
                return new ComprehensionNode.ListComprehensionNode(targetSlot, comprehension);
            case TUPLE:
                return new ComprehensionNode.TupleComprehensionNode(targetSlot, comprehension);
            case SET:
                return new ComprehensionNode.SetComprehensionNode(targetSlot, comprehension);
            default:
                throw new IllegalStateException();
        }
    }

    public PNode createComprehensionAppendNode(FrameSlot targetSlot, PNode comprehension) {
        switch (this) {
            case LIST:
                PNode list = ReadLocalVariableNode.create(targetSlot);
                return ListAppendNodeFactory.create(list, comprehension);
            case TUPLE:
                return ArrayListAddNodeFactory.create(targetSlot, comprehension);
            case SET:
                return TreeSetAddNodeFactory.create(targetSlot, comprehension);
            default:
                throw new IllegalStateException();
        }
    }

}
