/*
 * Copyright (c) 2010, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.nashorn.internal.objects;

import static jdk.nashorn.internal.runtime.ScriptRuntime.UNDEFINED;

import java.lang.invoke.MethodHandle;
import jdk.nashorn.internal.runtime.GlobalFunctions;
import jdk.nashorn.internal.runtime.Property;
import jdk.nashorn.internal.runtime.PropertyMap;
import jdk.nashorn.internal.runtime.RecompilableScriptFunctionData;
import jdk.nashorn.internal.runtime.ScriptFunction;
import jdk.nashorn.internal.runtime.ScriptFunctionData;
import jdk.nashorn.internal.runtime.ScriptObject;
import jdk.nashorn.internal.lookup.Lookup;

/**
 * Concrete implementation of ScriptFunction. This sets correct map for the
 * function objects -- to expose properties like "prototype", "length" etc.
 */
public class ScriptFunctionImpl extends ScriptFunction {

    /** Reference to constructor prototype. */
    private Object prototype;

    // property map for strict mode functions
    private static final PropertyMap strictmodemap$;
    // property map for bound functions
    private static final PropertyMap boundfunctionmap$;
    // property map for non-strict, non-bound functions.
    private static final PropertyMap map$;

    // Marker object for lazily initialized prototype object
    private static final Object LAZY_PROTOTYPE = new Object();

    /**
     * Constructor called by Nasgen generated code, no membercount, use the default map.
     * Creates builtin functions only.
     *
     * @param name name of function
     * @param invokeHandle handle for invocation
     * @param specs specialized versions of this method, if available, null otherwise
     */
    ScriptFunctionImpl(final String name, final MethodHandle invokeHandle, final MethodHandle[] specs) {
        super(name, invokeHandle, map$, null, specs, false, true, true);
        init();
    }

    /**
     * Constructor called by Nasgen generated code, no membercount, use the map passed as argument.
     * Creates builtin functions only.
     *
     * @param name name of function
     * @param invokeHandle handle for invocation
     * @param map initial property map
     * @param specs specialized versions of this method, if available, null otherwise
     */
    ScriptFunctionImpl(final String name, final MethodHandle invokeHandle, final PropertyMap map, final MethodHandle[] specs) {
        super(name, invokeHandle, map.addAll(map$), null, specs, false, true, true);
        init();
    }

    /**
     * Constructor called by Global.newScriptFunction (runtime).
     *
     * @param name name of function
     * @param methodHandle handle for invocation
     * @param scope scope object
     * @param specs specialized versions of this method, if available, null otherwise
     * @param isStrict are we in strict mode
     * @param isBuiltin is this a built-in function
     * @param isConstructor can the function be used as a constructor (most can; some built-ins are restricted).
     */
    ScriptFunctionImpl(final String name, final MethodHandle methodHandle, final ScriptObject scope, final MethodHandle[] specs, final boolean isStrict, final boolean isBuiltin, final boolean isConstructor) {
        super(name, methodHandle, getMap(isStrict), scope, specs, isStrict, isBuiltin, isConstructor);
        init();
    }

    /**
     * Constructor called by (compiler) generated code for {@link ScriptObject}s.
     *
     * @param data static function data
     * @param scope scope object
     */
    public ScriptFunctionImpl(final RecompilableScriptFunctionData data, final ScriptObject scope) {
        super(data, getMap(data.isStrict()), scope);
        init();
    }

    /**
     * Only invoked internally from {@link BoundScriptFunctionImpl} constructor.
     * @param data the script function data for the bound function.
     */
    ScriptFunctionImpl(final ScriptFunctionData data) {
        super(data, boundfunctionmap$, null);
        init();
    }

    static {
        PropertyMap map = PropertyMap.newMap(ScriptFunctionImpl.class);
        map = Lookup.newProperty(map, "prototype", Property.NOT_ENUMERABLE | Property.NOT_CONFIGURABLE, G$PROTOTYPE, S$PROTOTYPE);
        map = Lookup.newProperty(map, "length",    Property.NOT_ENUMERABLE | Property.NOT_CONFIGURABLE | Property.NOT_WRITABLE, G$LENGTH, null);
        map = Lookup.newProperty(map, "name",      Property.NOT_ENUMERABLE | Property.NOT_CONFIGURABLE | Property.NOT_WRITABLE, G$NAME, null);
        map$ = map;
        strictmodemap$ = createStrictModeMap(map$);
        boundfunctionmap$ = createBoundFunctionMap(strictmodemap$);
    }

    // function object representing TypeErrorThrower
    private static ScriptFunction typeErrorThrower;

    /*
     * ECMA section 13.2.3 The [[ThrowTypeError]] Function Object
     */
    static synchronized ScriptFunction getTypeErrorThrower() {
        if (typeErrorThrower == null) {
            // use "getter" so that [[ThrowTypeError]] function's arity is 0 - as specified in step 10 of section 13.2.3
            final ScriptFunctionImpl func = new ScriptFunctionImpl("TypeErrorThrower", Lookup.TYPE_ERROR_THROWER_GETTER, null, null, false, false, false);
            func.setPrototype(UNDEFINED);
            // Non-constructor built-in functions do not have "prototype" property
            func.deleteOwnProperty(func.getMap().findProperty("prototype"));
            func.preventExtensions();
            typeErrorThrower = func;
        }

        return typeErrorThrower;
    }

    private static PropertyMap createStrictModeMap(PropertyMap map) {
        final int flags = Property.NOT_ENUMERABLE | Property.NOT_CONFIGURABLE;
        // Need to add properties directly to map since slots are assigned speculatively by newUserAccessors.
        map = map.addProperty(map.newUserAccessors("arguments", flags));
        map = map.addProperty(map.newUserAccessors("caller", flags));
        return map;
    }

    // Choose the map based on strict mode!
    private static PropertyMap getMap(final boolean strict) {
        return strict ? strictmodemap$ : map$;
    }

    private static PropertyMap createBoundFunctionMap(final PropertyMap strictModeMap) {
        // Bound function map is same as strict function map, but additionally lacks the "prototype" property, see
        // ECMAScript 5.1 section 15.3.4.5
        return strictModeMap.deleteProperty(strictModeMap.findProperty("prototype"));
    }

    // Instance of this class is used as global anonymous function which
    // serves as Function.prototype object.
    private static class AnonymousFunction extends ScriptFunctionImpl {
        private static final PropertyMap nasgenmap$$ = PropertyMap.newMap(AnonymousFunction.class);

        AnonymousFunction() {
            super("", GlobalFunctions.ANONYMOUS, nasgenmap$$, null);
        }
    }

    static ScriptFunctionImpl newAnonymousFunction() {
        return new AnonymousFunction();
    }

    /**
     * Factory method for non-constructor built-in functions
     *
     * @param name   function name
     * @param methodHandle handle for invocation
     * @param specs  specialized versions of function if available, null otherwise
     * @return new ScriptFunction
     */
    static ScriptFunction makeFunction(final String name, final MethodHandle methodHandle, final MethodHandle[] specs) {
        final ScriptFunctionImpl func = new ScriptFunctionImpl(name, methodHandle, null, specs, false, true, false);
        func.setPrototype(UNDEFINED);
        // Non-constructor built-in functions do not have "prototype" property
        func.deleteOwnProperty(func.getMap().findProperty("prototype"));

        return func;
    }

    /**
     * Factory method for non-constructor built-in functions
     *
     * @param name   function name
     * @param methodHandle handle for invocation
     * @return new ScriptFunction
     */
    static ScriptFunction makeFunction(final String name, final MethodHandle methodHandle) {
        return makeFunction(name, methodHandle, null);
    }

    /**
     * Same as {@link ScriptFunction#makeBoundFunction(Object, Object[])}. The only reason we override it is so that we
     * can expose it to methods in this package.
     * @param self the self to bind to this function. Can be null (in which case, null is bound as this).
     * @param args additional arguments to bind to this function. Can be null or empty to not bind additional arguments.
     * @return a function with the specified self and parameters bound.
     */
    @Override
    protected ScriptFunction makeBoundFunction(Object self, Object[] args) {
        return super.makeBoundFunction(self, args);
    }

    /**
     * This method is used to create a bound function based on this function.
     *
     * @param data the {@code ScriptFunctionData} specifying the functions immutable portion.
     * @return a function initialized from the specified data. Its parent scope will be set to null, therefore the
     * passed in data should not expect a callee.
     */
    @Override
    protected ScriptFunction makeBoundFunction(final ScriptFunctionData data) {
        return new BoundScriptFunctionImpl(data, getTargetFunction());
    }

    // return Object.prototype - used by "allocate"
    @Override
    protected final ScriptObject getObjectPrototype() {
        return Global.objectPrototype();
    }

    @Override
    public final Object getPrototype() {
        if (prototype == LAZY_PROTOTYPE) {
            prototype = new PrototypeObject(this);
        }
        return prototype;
    }

    @Override
    public final void setPrototype(final Object prototype) {
        this.prototype = prototype;
    }

    // Internals below..
    private void init() {
        this.setProto(Global.instance().getFunctionPrototype());
        this.prototype = LAZY_PROTOTYPE;

        // We have to fill user accessor functions late as these are stored
        // in this object rather than in the PropertyMap of this object.

        if (findProperty("arguments", true) != null) {
            setUserAccessors("arguments", getTypeErrorThrower(), getTypeErrorThrower());
        }

        if (findProperty("caller", true) != null) {
            setUserAccessors("caller", getTypeErrorThrower(), getTypeErrorThrower());
        }
    }
}
