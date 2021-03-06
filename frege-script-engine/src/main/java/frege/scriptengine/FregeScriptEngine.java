package frege.scriptengine;

import frege.Prelude;
import frege.compiler.types.Global;
import frege.compiler.types.Symbols;
import frege.interpreter.FregeInterpreter;
import frege.interpreter.FregeInterpreter.TInterpreterResult;
import frege.interpreter.javasupport.InterpreterClassLoader;
import frege.interpreter.javasupport.JavaUtils;
import frege.interpreter.javasupport.Ref;
import frege.prelude.PreludeBase;
import frege.prelude.PreludeBase.TList;
import frege.prelude.PreludeMonad;
import frege.prelude.PreludeText;
import frege.runtime.Lambda;
import frege.runtime.Lazy;

import javax.script.AbstractScriptEngine;
import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineFactory;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import java.io.Reader;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

public class FregeScriptEngine extends AbstractScriptEngine implements
    Compilable {

    private static final String FREGE_PRELUDE_SCRIPT_KEY = "frege.scriptengine.preludeScript";
    private static final String FREGE_BINDINGS_KEY = "frege.scriptengine.bindings";
    private static final String PRELUDE_SCRIPT_CLASS_NAME = "frege.scriptengine.PreludeScript";
    private static final String CONFIG_KEY = "frege.scriptengine.currentDefs";
    private static final String CLASSLOADER_KEY = "frege.scriptengine.classloader";

    private final ScriptEngineFactory factory;

    private static String preludeDef = "module "
        + PRELUDE_SCRIPT_CLASS_NAME + " where\n"
        + "data FregeScriptEngineRef a = pure native " + Ref.class.getCanonicalName() + " where\n"
        + "  native new :: () -> ST s (FregeScriptEngineRef a)\n"
        + "  pure native get :: FregeScriptEngineRef a -> a\n";

    public FregeScriptEngine(final ScriptEngineFactory factory) {
        this.factory = factory;
        getContext().setAttribute(FREGE_PRELUDE_SCRIPT_KEY, preludeDef,
            ScriptContext.ENGINE_SCOPE);
    }

    @Override
    public Object eval(final String script, final ScriptContext context)
        throws ScriptException {
        final Lambda res = FregeInterpreter.interpret(script);
        final PreludeBase.TTuple2 intpRes = FregeInterpreter.TInterpreter.run(
            res, config(context), classLoader(context)).forced();
        return toEvalResult(intpRes, context, script);
    }

    private InterpreterClassLoader classLoader(final ScriptContext context) {
        InterpreterClassLoader classLoader = (InterpreterClassLoader) context.getAttribute(CLASSLOADER_KEY);
        if (classLoader == null) {
            classLoader = new InterpreterClassLoader();
        }
        return classLoader;
    }

    private FregeInterpreter.TInterpreterConfig config(final ScriptContext context) {
        FregeInterpreter.TInterpreterConfig config = (FregeInterpreter.TInterpreterConfig) context.getAttribute(CONFIG_KEY);
        if (config == null) {
            config = toJavaValue(FregeInterpreter.TInterpreterConfig._default);
        }
        return config;
    }

    private Object toEvalResult(final PreludeBase.TTuple2 tup, final ScriptContext context,
                                final String script) throws ScriptException {
        final FregeInterpreter.TInterpreterResult interpRes = toJavaValue(tup.mem1);
        final InterpreterClassLoader classLoader = toJavaValue(tup.mem2);
        Object res = null;
        switch (interpRes._constructor()) {
            case 0: //Success
                TInterpreterResult.DSuccess success = interpRes._Success();
                final FregeInterpreter.TSourceInfo srcinfo = toJavaValue(success.mem$sourceRepr);
                final Global.TGlobal compilerState = toJavaValue(success.mem$compilerState);
                switch (srcinfo._constructor()) {
                    case 0: //Module
                        context.setAttribute(CLASSLOADER_KEY, classLoader, ScriptContext.ENGINE_SCOPE);
                        break;
                    case 1: //Expression
                        final Symbols.TSymbolT sym = toJavaValue(srcinfo._Expression().mem1);
                        final String className = toJavaValue(FregeInterpreter.symbolClass(sym, compilerState));
                        final String varName = toJavaValue(FregeInterpreter.symbolVar(sym, compilerState));
                        res = evalSym(context, classLoader, className, varName);
                        break;
                    case 2: //Definitions
                        final FregeInterpreter.TInterpreterConfig config = config(context);
                        final TList newDefs = TList.DCons.mk(script, config.mem$predefs);
                        context.setAttribute(CONFIG_KEY,
                            FregeInterpreter.TInterpreterConfig.upd$predefs(config, newDefs),
                            ScriptContext.ENGINE_SCOPE);
                        break;
                }
                break;
            case 1:
                TInterpreterResult.DFailure failure = interpRes._Failure();
                final TList msgs = toJavaValue(failure.mem1);
                final String errorMsg = toJavaValue(
                    FregeInterpreter.TMessage.showMessages(FregeInterpreter.IShow_Message.it, msgs));
                throw new ScriptException(errorMsg);

        }
        return res;
    }

    private Object evalSym(final ScriptContext context,
                           final InterpreterClassLoader classLoader,
                           final String className,
                           final String varName) throws ScriptException {
        final Object res;
        Map<String, Object> bindings = (Map<String, Object>) context.getAttribute(FREGE_BINDINGS_KEY);
        try {
            if (bindings != null && !bindings.isEmpty()) {
                Class<?> preludeClass = classLoader.loadClass(PRELUDE_SCRIPT_CLASS_NAME);
                JavaUtils.injectValues(bindings, preludeClass);
            }
            res = JavaUtils.fieldValue(className, varName, classLoader);
        } catch (Throwable throwable) {
            throw new ScriptException(throwable.toString());
        }
        return res;
    }

    private void loadScriptingPrelude(final ScriptContext context) {
        final String script = (String) context.getAttribute(
            FREGE_PRELUDE_SCRIPT_KEY, ScriptContext.ENGINE_SCOPE);
        try {
            eval(script, context);
        } catch (ScriptException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public Object eval(final Reader reader, final ScriptContext context)
        throws ScriptException {
        final String script = slurp(reader);
        return eval(script, context);

    }

    public static <A> A toJavaValue(final Object obj) {
        final A result;
        if (obj instanceof Lazy) {
            result = ((Lazy) obj).forced();
        } else {
            result = (A) obj;
        }
        return result;
    }

    private static String slurp(final Reader reader) {
        try (final Scanner scanner = new Scanner(reader)) {
            scanner.useDelimiter("\\Z");
            if (scanner.hasNext())
                return scanner.next();
        }
        return "";
    }

    @Override
    public Bindings createBindings() {
        return new SimpleBindings();
    }

    @Override
    public ScriptEngineFactory getFactory() {
        return this.factory;
    }

    @Override
    public CompiledScript compile(final String script) throws ScriptException {
        final Lambda res = FregeInterpreter.interpret(script);
        final PreludeBase.TTuple2 intpRes = FregeInterpreter.TInterpreter.run(
            res, config(context), classLoader(context)).forced();
        return new CompiledScript() {
            @Override
            public Object eval(final ScriptContext context) throws ScriptException {
                return toEvalResult(intpRes, context, script);
            }

            @Override
            public ScriptEngine getEngine() {
                return FregeScriptEngine.this;
            }
        };
    }

    @Override
    public CompiledScript compile(final Reader reader) throws ScriptException {
        return compile(slurp(reader));
    }

    @Override
    public void put(final String key, final Object value) {
        final String[] nameAndType = key.split("::");
        final String name = nameAndType[0].trim();
        final String type = nameAndType.length < 2 ? "a" : nameAndType[1].trim();
        updateCurrentScript(name, type);
        updatePreludeScript(name, type);
        updateBindings(value, name);
        loadScriptingPrelude(context);
        super.put(name, value);
    }

    private void updateBindings(final Object value, final String name) {
        if (context.getAttribute(FREGE_BINDINGS_KEY, ScriptContext.ENGINE_SCOPE) == null) {
            context.setAttribute(FREGE_BINDINGS_KEY, new HashMap<String, Object>(),
                ScriptContext.ENGINE_SCOPE);
        }
        final Map<String, Object> bindings = (Map<String, Object>) context
            .getAttribute(FREGE_BINDINGS_KEY, ScriptContext.ENGINE_SCOPE);
        bindings.put(name + "Ref", value);
    }

    private void updateCurrentScript(final String name, final String type) {
        FregeInterpreter.TInterpreterConfig config = config(context);
        final Object bindings = context.getAttribute(FREGE_BINDINGS_KEY, ScriptContext.ENGINE_SCOPE);
        final boolean includePreludeImport = bindings == null;
        final String newScript = String.format(
            "\n%1$s :: %2$s\n%1$s = FregeScriptEngineRef.get %3$s", name, type, name + "Ref");
        final TList predefs;
        if (includePreludeImport) {
            final String preludeImport = "\nimport " + PRELUDE_SCRIPT_CLASS_NAME + "\n";
            predefs = TList.DCons.mk(newScript, TList.DCons.mk(preludeImport, config.mem$predefs));

        } else {
            predefs = TList.DCons.mk(newScript, config.mem$predefs);
        }
        final FregeInterpreter.TInterpreterConfig newConfig =
            FregeInterpreter.TInterpreterConfig.upd$predefs(config, predefs);
        context.setAttribute(CONFIG_KEY, newConfig, ScriptContext.ENGINE_SCOPE);
    }

    private void updatePreludeScript(final String name, final String type) {
        final String typ = "FregeScriptEngineRef (" + type + ")";
        final String newDef = String.format("\n%1$sRef :: %2$s\n"
            + "!%1$sRef = IO.performUnsafe $ FregeScriptEngineRef.new ()\n", name, typ);
        final String preludeScript = (String) context.getAttribute(
            FREGE_PRELUDE_SCRIPT_KEY, ScriptContext.ENGINE_SCOPE);
        final String newPreludeScript = preludeScript + newDef;
        context.setAttribute(FREGE_PRELUDE_SCRIPT_KEY, newPreludeScript,
            ScriptContext.ENGINE_SCOPE);
    }

}
