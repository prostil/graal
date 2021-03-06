/*
 * Copyright (c) 2019, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.polyglot;

import java.lang.ref.WeakReference;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.TruffleLanguage;
import com.oracle.truffle.api.TruffleLanguage.ContextPolicy;
import com.oracle.truffle.api.TruffleLanguage.ContextReference;
import com.oracle.truffle.api.TruffleLanguage.LanguageReference;
import com.oracle.truffle.api.TruffleStackTrace;
import com.oracle.truffle.api.TruffleStackTraceElement;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;

final class PolyglotReferences {

    private PolyglotReferences() {
        // no instances
    }

    static ContextReference<Object> createAlwaysSingleContext(PolyglotLanguage language) {
        return new SingleContext(language);
    }

    static ContextReference<Object> createAssumeSingleContext(PolyglotLanguage language,
                    Assumption validIf0,
                    Assumption validIf1,
                    ContextReference<Object> fallback) {
        assert !(fallback instanceof SingleContext);
        return new AssumeSingleContext(language, validIf0, validIf1, fallback);
    }

    static ContextReference<Object> createAlwaysMultiContext(PolyglotLanguage language) {
        return new MultiContextSupplier(language);
    }

    static LanguageReference<TruffleLanguage<Object>> createAlwaysSingleLanguage(PolyglotLanguage language, PolyglotLanguageInstance initValue) {
        return new SingleLanguage(language, initValue);
    }

    static LanguageReference<TruffleLanguage<Object>> createAssumeSingleLanguage(PolyglotLanguage language,
                    PolyglotLanguageInstance initValue,
                    Assumption validIf,
                    LanguageReference<TruffleLanguage<Object>> fallback) {
        assert !(fallback instanceof SingleLanguage);
        return new AssumeSingleLanguage(language, initValue, validIf, fallback);
    }

    static LanguageReference<TruffleLanguage<Object>> createAlwaysMultiLanguage(PolyglotLanguage language) {
        return new MultiLanguageSupplier(language);
    }

    private static AssertionError invalidSharingError(PolyglotEngineImpl usedEngine) throws AssertionError {
        Exception e = new Exception();
        StringBuilder stack = new StringBuilder();
        Exception exceptionCreating = null;
        try {
            TruffleStackTrace.fillIn(e);
            ContextPolicy prevPolicy = null;
            for (TruffleStackTraceElement stackTrace : TruffleStackTrace.getStackTrace(e)) {
                RootNode root = stackTrace.getTarget().getRootNode();
                PolyglotEngineImpl engine = (PolyglotEngineImpl) VMAccessor.NODES.getSourceVM(root);
                if (engine != null && usedEngine != engine) {
                    // different engine different assertion
                    break;
                }
                PolyglotLanguageInstance instance = lookupLanguageInstance(root);
                ContextPolicy policy = instance.getEffectiveContextPolicy(instance);

                SourceSection sourceSection = null;
                Node location = stackTrace.getLocation();
                if (location != null) {
                    sourceSection = location.getEncapsulatingSourceSection();
                }
                if (sourceSection == null) {
                    sourceSection = root.getSourceSection();
                }
                if ((prevPolicy == ContextPolicy.EXCLUSIVE || policy == ContextPolicy.EXCLUSIVE) && prevPolicy != policy && prevPolicy != null) {
                    stack.append(String.format("    <-- Likely Invalid Sharing --> %n"));
                }
                stack.append(String.format("  %-9s %s%n", policy, createJavaStackFrame(instance.language, root.getName(), sourceSection)));
                prevPolicy = policy;
            }
        } catch (Exception ex) {
            exceptionCreating = ex;
        }
        AssertionError error = new AssertionError(String.format("Invalid sharing of runtime values in AST nodes detected.Stack trace: %n%s", stack.toString()));
        if (exceptionCreating != null) {
            error.addSuppressed(exceptionCreating);
        }
        return error;
    }

    static StackTraceElement createJavaStackFrame(PolyglotLanguage language, String rootName, SourceSection sourceLocation) {
        String declaringClass = "<" + language.getId() + ">";
        String methodName = rootName == null ? "" : rootName;
        String fileName = sourceLocation != null ? sourceLocation.getSource().getName() : "Unknown";
        int startLine = sourceLocation != null ? sourceLocation.getStartLine() : -1;
        return new StackTraceElement(declaringClass, methodName, fileName, startLine);
    }

    private static PolyglotLanguageInstance lookupLanguageInstance(RootNode root) {
        TruffleLanguage<?> spi = VMAccessor.NODES.getLanguage(root);
        if (spi != null) {
            return (PolyglotLanguageInstance) VMAccessor.LANGUAGE.getLanguageInstance(spi);
        }
        return null;
    }

    private static final class SingleLanguage extends LanguageReference<TruffleLanguage<Object>> {

        private final PolyglotLanguage language;
        @CompilationFinal private TruffleLanguage<Object> spi;

        @SuppressWarnings("unchecked")
        SingleLanguage(PolyglotLanguage language, PolyglotLanguageInstance initValue) {
            this.language = language;
            this.spi = initValue != null ? initValue.spi : null;
        }

        @SuppressWarnings("unchecked")
        @Override
        public TruffleLanguage<Object> get() {
            assert language.assertCorrectEngine();
            TruffleLanguage<Object> languageSpi = this.spi;
            if (languageSpi == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                this.spi = languageSpi = language.getCurrentLanguageContext().getLanguageInstance().spi;
            }
            assert assertDirectLanguageAccess(this.language, languageSpi);
            return languageSpi;
        }

        private static boolean assertDirectLanguageAccess(PolyglotLanguage language, TruffleLanguage<Object> seenLanguage) {
            TruffleLanguage<?> conservativeLanguage = language.getConservativeLanguageReference().get();
            if (conservativeLanguage != seenLanguage) {
                throw invalidSharingError(language.getEngine());
            }
            return true;
        }

    }

    private static final class SingleContext extends ContextReference<Object> {

        private final PolyglotLanguage language;
        @CompilationFinal private volatile WeakReference<Object> languageContextImpl;

        // only set if assertions are enabled
        private volatile WeakReference<PolyglotLanguageContext> languageContext;

        SingleContext(PolyglotLanguage language) {
            this.language = language;
        }

        @Override
        public Object get() {
            assert language.assertCorrectEngine();
            WeakReference<Object> ref = languageContextImpl;
            if (ref == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                PolyglotLanguageContext langContext = language.getCurrentLanguageContext();
                assert setLanguageContext(langContext);
                this.languageContextImpl = ref = new WeakReference<>(langContext.getContextImpl());
            }
            Object context = ref.get();
            assert checkContextCollected(context);
            assert assertDirectContextAccess(context, this.languageContext);
            return context;
        }

        private static boolean assertDirectContextAccess(Object seenContext, WeakReference<PolyglotLanguageContext> contextRef) {
            if (contextRef == null) {
                /*
                 * This case may happen if the assertions were disabled during boot image generation
                 * but were later enabled at runtime. See GR-14463.
                 */
                return true;
            }
            PolyglotLanguageContext context = contextRef.get();
            if (context == null) {
                throw invalidSharingError(null);
            }
            PolyglotContextImpl otherContext = PolyglotContextImpl.requireContext();
            PolyglotLanguageContext otherLanguageContext = otherContext.getContext(context.language);
            boolean valid = otherLanguageContext.getContextImpl() == seenContext;
            if (!valid) {
                throw invalidSharingError(context.getEngine());
            }
            return true;
        }

        private static boolean checkContextCollected(Object context) {
            if (context == null) {
                throw invalidSharingError(null);
            }
            return true;
        }

        private boolean setLanguageContext(PolyglotLanguageContext langContext) {
            this.languageContext = new WeakReference<>(langContext);
            return true;
        }

    }

    private static final class AssumeSingleContext extends ContextReference<Object> {

        private final SingleContext singleContextReference;
        private final ContextReference<Object> fallbackReference;
        private final Assumption validIf0;
        private final Assumption validIf1;

        AssumeSingleContext(PolyglotLanguage language, Assumption validIf0, Assumption validIf1, ContextReference<Object> fallback) {
            this.validIf0 = validIf0;
            this.validIf1 = validIf1;
            this.singleContextReference = (SingleContext) createAlwaysSingleContext(language);
            this.fallbackReference = fallback;
        }

        @Override
        public Object get() {
            Object context;
            if (validIf0.isValid() && (validIf1 == null || validIf1.isValid())) {
                context = singleContextReference.get();
            } else {
                context = fallbackReference.get();
            }
            assert fallbackReference.get() == context;
            return context;
        }
    }

    private static final class AssumeSingleLanguage extends LanguageReference<TruffleLanguage<Object>> {

        private final LanguageReference<TruffleLanguage<Object>> singleLanguageReference;
        private final LanguageReference<TruffleLanguage<Object>> fallbackReference;
        private final Assumption singleLanguage;

        AssumeSingleLanguage(PolyglotLanguage language, PolyglotLanguageInstance initValue, Assumption singleContextAssumption, LanguageReference<TruffleLanguage<Object>> fallbackReference) {
            this.singleLanguage = singleContextAssumption;
            this.singleLanguageReference = createAlwaysSingleLanguage(language, initValue);
            this.fallbackReference = fallbackReference;
        }

        @Override
        public TruffleLanguage<Object> get() {
            if (singleLanguage.isValid()) {
                return singleLanguageReference.get();
            }
            return fallbackReference.get();
        }
    }

    private static final class MultiLanguageSupplier extends LanguageReference<TruffleLanguage<Object>> {

        final PolyglotLanguage language;

        MultiLanguageSupplier(PolyglotLanguage language) {
            this.language = language;
        }

        @SuppressWarnings("unchecked")
        @Override
        public TruffleLanguage<Object> get() {
            assert language.assertCorrectEngine();
            return PolyglotContextImpl.requireContext().getContext(language).getLanguageInstance().spi;
        }
    }

    private static final class MultiContextSupplier extends ContextReference<Object> {

        final PolyglotLanguage language;

        MultiContextSupplier(PolyglotLanguage language) {
            this.language = language;
        }

        @Override
        public Object get() {
            assert language.assertCorrectEngine();
            return PolyglotContextImpl.requireContext().getContext(language).getContextImpl();
        }
    }

}
