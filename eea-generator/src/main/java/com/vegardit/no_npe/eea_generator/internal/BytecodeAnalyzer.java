/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.FieldInfo;
import io.github.classgraph.MethodInfo;
import io.github.classgraph.ScanResult;

/**
 * Infers conservative nullness contracts from JVM bytecode and closed-world classpath evidence.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class BytecodeAnalyzer {

   /* Method summaries recurse through calls even though instruction provenance is evaluated iteratively. This stays well
    * below ordinary JVM stack limits while leaving ample room for realistic helper chains. */
   private static final int MAX_METHOD_SUMMARY_DEPTH = 128;

   /* Nullness inference is optional evidence, so adversarial or unusually large dependency bytecode should reduce
    * precision instead of making generation consume unbounded memory. The instruction ceiling deliberately retains
    * the existing 20,000-instruction stress cases; the tighter frame and exception-edge limits catch memory-heavy
    * methods below that ceiling. */
   private static final int MAX_ANALYSIS_INSTRUCTIONS = 50_000;
   private static final long MAX_ANALYSIS_FRAME_VALUES = 1_000_000;
   private static final long MAX_ANALYSIS_EXCEPTION_EDGES = 250_000;
   /* Field-fact sets can grow quadratically even when ASM frame sizes stay small. Bound the values copied or compared
    * across the optional refinement and fall back to unknown facts before a crafted method exhausts heap or CPU. */
   private static final long MAX_FIELD_FACT_ANALYSIS_WORK = 1_000_000;

   public enum Nullability {
      /** a method never returns null */
      NEVER_NULL,

      /** at least one code branch definitely returns null */
      DEFINITELY_NULL,

      /** a method may or may not return null */
      UNKNOWN,

      /** a method only returns null when an argument is null */
      POLY_NULL
   }

   @SuppressWarnings("null")
   private static final class ControlFlowAnalyzer extends Analyzer<SourceValue> {
      final List<Set<Integer>> exceptionSuccessors;
      final List<Set<Integer>> normalSuccessors;

      ControlFlowAnalyzer(final int instructionCount) {
         super(new SourceInterpreter());
         normalSuccessors = new ArrayList<>(instructionCount);
         exceptionSuccessors = new ArrayList<>(instructionCount);
         for (int i = 0; i < instructionCount; i++) {
            normalSuccessors.add(new HashSet<>());
            exceptionSuccessors.add(new HashSet<>());
         }
      }

      @Override
      protected void newControlFlowEdge(final int instructionIndex, final int successorIndex) {
         normalSuccessors.get(instructionIndex).add(successorIndex);
      }

      @Override
      protected boolean newControlFlowExceptionEdge(final int instructionIndex, final int successorIndex) {
         exceptionSuccessors.get(instructionIndex).add(successorIndex);
         return true;
      }
   }

   private static final class FieldFactBudget {
      private long remainingWork = MAX_FIELD_FACT_ANALYSIS_WORK;

      boolean tryConsume(final long workUnits) {
         if (workUnits > remainingWork) {
            // All refinement phases share this object; once exceeded, later phases must not start a fresh allowance.
            remainingWork = 0;
            return false;
         }
         remainingWork -= workUnits;
         return true;
      }
   }

   private enum FlowNullness {
      DEFINITELY_NULL,
      MAY_INCLUDE_NULL,
      NEVER_NULL,
      UNKNOWN;

      static FlowNullness merge(final FlowNullness first, final FlowNullness second) {
         if (first == second)
            return first;
         if (first == MAY_INCLUDE_NULL || second == MAY_INCLUDE_NULL || first == DEFINITELY_NULL || second == DEFINITELY_NULL)
            // One reachable producer is proven null, even if the other producer itself remains unknown.
            return MAY_INCLUDE_NULL;
         return UNKNOWN;
      }
   }

   private static final class FlowValue extends SourceValue {
      final boolean mayHaveNonConstantNullPath;
      final boolean nullConstantPath;
      final FlowNullness nullness;
      final Set<Integer> parameterLocalSlots;
      final @Nullable String privateThisFieldKey;
      final Set<String> requiredNonNullFieldsForNullConstantPath;

      FlowValue(final SourceValue source, final Set<Integer> parameterLocalSlots, final FlowNullness nullness,
            final boolean nullConstantPath, final boolean mayHaveNonConstantNullPath, final @Nullable String privateThisFieldKey,
            final Set<String> requiredNonNullFieldsForNullConstantPath) {
         super(source.size, source.insns);
         this.parameterLocalSlots = Set.copyOf(parameterLocalSlots);
         this.nullness = nullness;
         this.nullConstantPath = nullConstantPath;
         this.mayHaveNonConstantNullPath = mayHaveNonConstantNullPath;
         this.privateThisFieldKey = privateThisFieldKey;
         this.requiredNonNullFieldsForNullConstantPath = Set.copyOf(requiredNonNullFieldsForNullConstantPath);
      }

      @Override
      public boolean equals(final @Nullable Object obj) {
         if (this == obj)
            return true;
         if (!(obj instanceof FlowValue))
            return false;
         final FlowValue other = (FlowValue) obj;
         return mayHaveNonConstantNullPath == other.mayHaveNonConstantNullPath && nullConstantPath == other.nullConstantPath
               && nullness == other.nullness && Objects.equals(privateThisFieldKey, other.privateThisFieldKey) && parameterLocalSlots
                  .equals(other.parameterLocalSlots) && requiredNonNullFieldsForNullConstantPath.equals(
                     other.requiredNonNullFieldsForNullConstantPath) && super.equals(other);
      }

      @Override
      public int hashCode() {
         return Objects.hash(super.hashCode(), parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath,
            privateThisFieldKey, requiredNonNullFieldsForNullConstantPath);
      }

      FlowValue withNullness(final FlowNullness refinedNullness) {
         /* A value proven non-null cannot still carry an ACONST_NULL path. Keep that provenance on the null edge so the
          * analysis distinguishes an explicit null constant from a nullable parameter that happens to be null. */
         final boolean refinedNullConstantPath = refinedNullness == FlowNullness.NEVER_NULL ? false : nullConstantPath;
         final boolean refinedNonConstantNullPath = refinedNullness == FlowNullness.NEVER_NULL ? false : mayHaveNonConstantNullPath;
         final Set<String> refinedRequiredFields = refinedNullConstantPath ? requiredNonNullFieldsForNullConstantPath : Set.of();
         return nullness == refinedNullness && nullConstantPath == refinedNullConstantPath
               && mayHaveNonConstantNullPath == refinedNonConstantNullPath ? this
                     : new FlowValue(this, parameterLocalSlots, refinedNullness, refinedNullConstantPath, refinedNonConstantNullPath,
                        privateThisFieldKey, refinedRequiredFields);
      }

      FlowValue withNullConstantRequirements(final Set<String> requiredFields) {
         return requiredNonNullFieldsForNullConstantPath.equals(requiredFields) ? this
               : new FlowValue(this, parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath, privateThisFieldKey,
                  requiredFields);
      }

      FlowValue withPrivateThisFieldKey(final String fieldKey) {
         return new FlowValue(this, parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath, fieldKey,
            requiredNonNullFieldsForNullConstantPath);
      }
   }

   private static final class FlowFrame extends Frame<SourceValue> {
      private @Nullable FlowFrame unrefinedJumpFrame;
      private @Nullable SourceValue testedValue;
      private int testedOpcode = -1;
      private boolean reachable = true;
      private final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction;

      FlowFrame(final int numLocals, final int numStack, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction) {
         super(numLocals, numStack);
         this.knownNonNullFieldsAtInstruction = knownNonNullFieldsAtInstruction;
      }

      FlowFrame(final Frame<? extends SourceValue> source, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction) {
         super(source);
         this.knownNonNullFieldsAtInstruction = knownNonNullFieldsAtInstruction;
         if (source instanceof FlowFrame) {
            final FlowFrame sourceFlowFrame = (FlowFrame) source;
            reachable = sourceFlowFrame.reachable;
         }
         // Branch state belongs only to the temporary frame executing that instruction and must not follow frame copies.
      }

      @Override
      @SuppressWarnings("null")
      @NonNullByDefault({})
      public void execute(final AbstractInsnNode instruction, final Interpreter<@NonNull SourceValue> interpreter)
            throws AnalyzerException {
         testedOpcode = -1;
         testedValue = null;
         unrefinedJumpFrame = null;

         final int opcode = instruction.getOpcode();
         if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && getStackSize() > 0) {
            testedOpcode = opcode;
            testedValue = getStack(getStackSize() - 1);
         }

         super.execute(instruction, interpreter);
         if (opcode == Opcodes.ACONST_NULL && getStackSize() > 0) {
            final SourceValue value = getStack(getStackSize() - 1);
            if (value instanceof FlowValue) {
               setStack(getStackSize() - 1, ((FlowValue) value).withNullConstantRequirements(knownNonNullFieldsAtInstruction.getOrDefault(
                  instruction, Set.of())));
            }
         } else if (opcode == Opcodes.GETFIELD && getStackSize() > 0) {
            final SourceValue value = getStack(getStackSize() - 1);
            if (value instanceof FlowValue) {
               final FlowValue flowValue = (FlowValue) value;
               if (flowValue.privateThisFieldKey != null && knownNonNullFieldsAtInstruction.getOrDefault(instruction, Set.of()).contains(
                  flowValue.privateThisFieldKey)) {
                  setStack(getStackSize() - 1, flowValue.withNullness(FlowNullness.NEVER_NULL));
               }
            }
         }
         if (testedValue != null) {
            // ASM executes a branch once, then reuses this frame while it initializes each normal successor.
            unrefinedJumpFrame = new FlowFrame(this, knownNonNullFieldsAtInstruction);
         }
      }

      @Override
      @NonNullByDefault({})
      public void initJumpTarget(final int opcode, final org.objectweb.asm.tree.LabelNode target) {
         final FlowFrame unrefinedFrame = unrefinedJumpFrame;
         final SourceValue tested = testedValue;
         if (opcode != testedOpcode || unrefinedFrame == null || !(tested instanceof FlowValue)) {
            super.initJumpTarget(opcode, target);
            return;
         }

         /* initJumpTarget is invoked once per normal edge. Restore the post-execution state so a refinement for the
          * fallthrough edge cannot leak into the jump edge. Exception handlers use ASM's separate pre-execution frame. */
         init(unrefinedFrame);
         reachable = unrefinedFrame.reachable;
         super.initJumpTarget(opcode, target);

         final boolean isNullEdge = opcode == Opcodes.IFNULL ? target != null : target == null;
         final FlowValue testedFlowValue = (FlowValue) tested;
         final boolean impossibleEdge = isNullEdge ? testedFlowValue.nullness == FlowNullness.NEVER_NULL
               : testedFlowValue.nullness == FlowNullness.DEFINITELY_NULL;
         if (impossibleEdge) {
            // ASM's structural CFG includes both conditional successors; the value proof makes this one impossible.
            reachable = false;
            return;
         }

         final FlowValue refinedValue = testedFlowValue.withNullness(isNullEdge ? FlowNullness.DEFINITELY_NULL : FlowNullness.NEVER_NULL);
         for (int i = 0; i < getLocals(); i++) {
            if (getLocal(i) == tested) {
               setLocal(i, refinedValue);
            }
         }
         for (int i = 0; i < getStackSize(); i++) {
            if (getStack(i) == tested) {
               setStack(i, refinedValue);
            }
         }
      }

      @Override
      @SuppressWarnings("null")
      @NonNullByDefault({})
      public boolean merge(final Frame<? extends SourceValue> incoming, final Interpreter<@NonNull SourceValue> interpreter)
            throws AnalyzerException {
         if (incoming instanceof FlowFrame) {
            final FlowFrame incomingFlowFrame = (FlowFrame) incoming;
            if (!incomingFlowFrame.reachable)
               return false;
            if (!reachable) {
               init(incomingFlowFrame);
               reachable = true;
               return true;
            }
         }
         return super.merge(incoming, interpreter);
      }
   }

   private static final class FlowAnalyzer extends Analyzer<SourceValue> {
      private final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction;

      FlowAnalyzer(final Interpreter<SourceValue> interpreter, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction) {
         super(interpreter);
         this.knownNonNullFieldsAtInstruction = knownNonNullFieldsAtInstruction;
      }

      @Override
      protected Frame<SourceValue> newFrame(final int numLocals, final int numStack) {
         return new FlowFrame(numLocals, numStack, knownNonNullFieldsAtInstruction);
      }

      @Override
      @SuppressWarnings("null")
      protected Frame<SourceValue> newFrame(final @NonNullByDefault({}) Frame<? extends SourceValue> frame) {
         return new FlowFrame(frame, knownNonNullFieldsAtInstruction);
      }
   }

   /* A proven summary means every normal reference return is either non-null or depends only on the listed parameters.
    * The empty proven set therefore means unconditionally non-null; the empty unproven set means unknown. */
   private static final class DependencySummary {
      static final DependencySummary NON_NULL = new DependencySummary(true, Set.of());
      static final DependencySummary UNKNOWN = new DependencySummary(false, Set.of());

      final Set<Integer> parameterIndexes;
      final boolean proven;

      private DependencySummary(final boolean proven, final Set<Integer> parameterIndexes) {
         this.proven = proven;
         this.parameterIndexes = Set.copyOf(parameterIndexes);
      }

      static DependencySummary dependentOn(final Set<Integer> parameterIndexes) {
         // A possibly-null value without a proven parameter dependency is unknown, not unconditionally non-null.
         if (parameterIndexes.isEmpty())
            return UNKNOWN;
         return new DependencySummary(true, parameterIndexes);
      }

      DependencySummary merge(final DependencySummary other) {
         if (!proven || !other.proven)
            return UNKNOWN;
         if (parameterIndexes.isEmpty())
            return other;
         if (other.parameterIndexes.isEmpty())
            return this;

         final Set<Integer> mergedIndexes = new HashSet<>(parameterIndexes);
         // A null value from either producer still implies that at least one parameter in the union was null.
         mergedIndexes.addAll(other.parameterIndexes);
         return new DependencySummary(true, mergedIndexes);
      }
   }

   private static final class DependencyExpansion {
      final @Nullable Set<AbstractInsnNode> dependencies;
      final DependencySummary summary;

      private DependencyExpansion(final DependencySummary summary, final @Nullable Set<AbstractInsnNode> dependencies) {
         this.summary = summary;
         this.dependencies = dependencies;
      }

      @SuppressWarnings("null")
      static DependencyExpansion forwarded(final SourceValue value) {
         return value.insns.isEmpty() ? terminal(DependencySummary.UNKNOWN)
               : new DependencyExpansion(DependencySummary.NON_NULL, value.insns);
      }

      static DependencyExpansion forwarded(final Set<AbstractInsnNode> dependencies) {
         return dependencies.isEmpty() ? terminal(DependencySummary.UNKNOWN)
               : new DependencyExpansion(DependencySummary.NON_NULL, dependencies);
      }

      static DependencyExpansion terminal(final DependencySummary summary) {
         return new DependencyExpansion(summary, null);
      }
   }

   private static final class DependencyFrame {
      final Iterator<AbstractInsnNode> dependencies;
      DependencySummary result = DependencySummary.NON_NULL;
      final AbstractInsnNode source;

      DependencyFrame(final AbstractInsnNode source, final Set<AbstractInsnNode> dependencies) {
         this.source = source;
         this.dependencies = dependencies.iterator();
      }
   }

   private static final class MethodSummaryDepthExceededException extends RuntimeException {
      private static final long serialVersionUID = 1L;
   }

   private enum ReturnEvidence {
      PROVEN_NULL,
      PROVEN_NON_NULL,
      UNKNOWN
   }

   private enum NonNullParameterEvidenceScope {
      RECEIVER_CALL_CONTRACT,
      RETURN_DEPENDENCY
   }

   private static final class MethodAnalysis {
      final Frame<SourceValue>[] frames;
      final List<Set<Integer>> guaranteedNonNullParameters;
      final List<Set<Integer>> guaranteedNullParameters;
      final boolean hasReceiver;
      final Map<AbstractInsnNode, Integer> instructionIndexes;
      final AbstractInsnNode[] instructions;
      final Map<Integer, Integer> referenceParameterIndexesByLocalSlot;

      MethodAnalysis(final AbstractInsnNode[] instructions, final Frame<SourceValue>[] frames,
            final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot,
            final List<Set<Integer>> guaranteedNullParameters, final List<Set<Integer>> guaranteedNonNullParameters,
            final boolean hasReceiver) {
         this.instructions = instructions;
         this.frames = frames;
         this.instructionIndexes = instructionIndexes;
         this.referenceParameterIndexesByLocalSlot = referenceParameterIndexesByLocalSlot;
         this.guaranteedNullParameters = guaranteedNullParameters;
         this.guaranteedNonNullParameters = guaranteedNonNullParameters;
         this.hasReceiver = hasReceiver;
      }
   }

   private static final class SourceProofFrame {
      final Iterator<AbstractInsnNode> dependencies;
      final AbstractInsnNode source;

      SourceProofFrame(final AbstractInsnNode source, final Set<AbstractInsnNode> dependencies) {
         this.source = source;
         this.dependencies = dependencies.iterator();
      }
   }

   @FunctionalInterface
   private interface SourceDependencyResolver {
      @Nullable
      Set<AbstractInsnNode> determineDependencies(AbstractInsnNode source);
   }

   @FunctionalInterface
   private interface DependencyExpansionResolver {
      DependencyExpansion determineExpansion(AbstractInsnNode source);
   }

   private static @Nullable ClassNode readExternalClass(final ScanResult scanResult, final String owner) throws IOException {
      try (var resources = scanResult.getResourcesWithPathIgnoringAccept(owner + ".class")) {
         if (resources.isEmpty())
            return null;

         /* ClassGraph returns duplicates in the same classpath order used by its classloader. Analyze the first
          * resource so all optional cross-class proofs follow the same shadowing rule as EEA generation. */
         try (@SuppressWarnings("resource")
         var is = resources.get(0).open()) {
            final var classReader = new ClassReader(is);
            final var resolvedClass = new ClassNode();
            classReader.accept(resolvedClass, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return owner.equals(resolvedClass.name) ? resolvedClass : null;
         }
      }
   }

   public static final class StaticFieldResolver {
      private final Map<String, Set<String>> definitelyNonNullFieldsByOwner = new HashMap<>();
      private final Map<String, ClassNode> registeredClassesByOwner = new HashMap<>();
      private final @Nullable ScanResult scanResult;

      private StaticFieldResolver() {
         // Standalone analyzers can still prove fields of the class they register, but have no classpath scope for external owners.
         scanResult = null;
      }

      public StaticFieldResolver(final ScanResult scanResult) {
         this.scanResult = scanResult;
      }

      private boolean isDefinitelyNonNull(final String owner, final String name, final String descriptor) {
         return definitelyNonNullFieldsByOwner.computeIfAbsent(owner, this::resolveDefinitelyNonNullFields).contains(fieldKey(name,
            descriptor));
      }

      @SuppressWarnings("null")
      private void register(final ClassNode resolvedClass) {
         /* A scan-backed resolver can reopen a class lazily and caches only its compact field summary. Retaining every
          * ClassNode here would otherwise keep the bytecode trees for the entire package scan alive. */
         if (scanResult == null && registeredClassesByOwner.putIfAbsent(resolvedClass.name, resolvedClass) == null) {
            // Registration supersedes a cached failed lookup for a standalone resolver.
            definitelyNonNullFieldsByOwner.remove(resolvedClass.name);
         }
      }

      private Set<String> resolveDefinitelyNonNullFields(final String owner) {
         final ClassNode registeredClass = registeredClassesByOwner.get(owner);
         if (registeredClass != null)
            return Set.copyOf(determineDefinitelyNonNullStaticFields(registeredClass));

         final ScanResult availableScanResult = scanResult;
         if (availableScanResult == null)
            return Set.of();

         try {
            final ClassNode resolvedClass = readExternalClass(availableScanResult, owner);
            if (resolvedClass == null)
               return Set.of();
            return Set.copyOf(determineDefinitelyNonNullStaticFields(resolvedClass));
         } catch (final IOException | IllegalArgumentException ex) {
            // External field proof is optional evidence; unreadable or unsupported class files must leave the field unknown.
            System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
               "Failed to analyze static fields of external class " + owner, ex);
            return Set.of();
         }
      }
   }

   public static final class MethodSummaryResolver {
      private final Map<String, ClassNode> classesByOwner = new HashMap<>();
      private final Map<String, DependencySummary> methodSummaries = new HashMap<>();
      private final Set<String> methodsBeingSummarized = new HashSet<>();
      private final Set<String> unresolvedOwners = new HashSet<>();
      private int methodSummaryDepth;
      private final @Nullable ScanResult scanResult;
      private final StaticFieldResolver staticFieldResolver;

      public MethodSummaryResolver(final StaticFieldResolver staticFieldResolver) {
         // Both resolvers must use the same classpath view or a method proof could depend on fields from another artifact version.
         scanResult = staticFieldResolver.scanResult;
         this.staticFieldResolver = staticFieldResolver;
      }

      @SuppressWarnings("null")
      private DependencySummary determineExternalMethodSummary(final MethodInsnNode call, final boolean receiverHasExactType) {
         final ClassNode resolvedOwner = resolveClass(call.owner);
         if (resolvedOwner == null)
            return DependencySummary.UNKNOWN;

         final MethodNode resolvedMethod = findMethodNode(resolvedOwner, call.name, call.desc);
         if (resolvedMethod == null)
            // Resolving inherited symbolic owners requires JVM method-resolution rules; an exact declaration is safe and sufficient here.
            return DependencySummary.UNKNOWN;

         final int opcode = call.getOpcode();
         final boolean ownerIsFinal = (resolvedOwner.access & Opcodes.ACC_FINAL) != 0;
         final boolean methodIsFinal = (resolvedMethod.access & Opcodes.ACC_FINAL) != 0;
         final boolean isOverridableVirtualCall = opcode == Opcodes.INVOKEVIRTUAL && !ownerIsFinal && !methodIsFinal;
         if (opcode == Opcodes.INVOKEINTERFACE || isOverridableVirtualCall && !receiverHasExactType)
            /* The classpath cannot prove that consumers will not supply another implementation or subclass. Analyze
             * only calls whose access flags or exact receiver allocation make the selected body the only runtime target. */
            return DependencySummary.UNKNOWN;

         /* Dispatch eligibility belongs to the call site and must stay above this cache. The cached value describes the
          * declared body only; returning it first could let an earlier exact call authorize a later arbitrary receiver. */
         final String key = call.owner + '\0' + methodKey(call.name, call.desc);
         final DependencySummary cached = methodSummaries.get(key);
         if (cached != null)
            return cached;
         if (!methodsBeingSummarized.add(key))
            // Cross-class recursion needs a fixed point; partial summaries must not become optimistic non-null evidence.
            return DependencySummary.UNKNOWN;

         DependencySummary result = DependencySummary.UNKNOWN;
         try {
            final var analyzer = new BytecodeAnalyzer(resolvedOwner, this);
            final DependencySummary resolvedSummary = analyzer.determineMethodDependencySummary(resolvedMethod);
            if (resolvedSummary.proven && resolvedSummary.parameterIndexes.isEmpty()) {
               /* Cross-class parameter dependencies would have to be remapped through every call site. For now, only
                * the parameter-independent result needed to establish an unconditional non-null return crosses owners. */
               result = resolvedSummary;
            }
         } finally {
            methodsBeingSummarized.remove(key);
         }
         /* Depth exhaustion escapes this block, so no caller can cache UNKNOWN merely because an earlier analysis
          * happened to reach the shared safety limit through a longer call chain. */
         methodSummaries.put(key, result);
         return result;
      }

      private void enterMethodSummary() {
         if (methodSummaryDepth >= MAX_METHOD_SUMMARY_DEPTH)
            throw new MethodSummaryDepthExceededException();
         methodSummaryDepth++;
      }

      private void exitMethodSummary() {
         methodSummaryDepth--;
      }

      private @Nullable ClassNode resolveClass(final String owner) {
         final ClassNode cached = classesByOwner.get(owner);
         if (cached != null)
            return cached;
         if (unresolvedOwners.contains(owner))
            return null;

         final ScanResult availableScanResult = scanResult;
         if (availableScanResult == null) {
            unresolvedOwners.add(owner);
            return null;
         }

         try {
            final ClassNode resolvedClass = readExternalClass(availableScanResult, owner);
            if (resolvedClass != null) {
               classesByOwner.put(owner, resolvedClass);
               return resolvedClass;
            }
         } catch (final IOException | IllegalArgumentException ex) {
            // External method proof is optional evidence; unreadable or unsupported class files leave the call unknown.
            System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
               "Failed to analyze methods of external class " + owner, ex);
         }
         unresolvedOwners.add(owner);
         return null;
      }
   }

   private final ClassNode classNode;
   private final Map<String, DependencySummary> methodDependencySummaries = new HashMap<>();
   private final MethodSummaryResolver methodSummaryResolver;
   private final Set<String> methodsBeingSummarized = new HashSet<>();
   private @Nullable Set<String> privateFieldsPreservingNonNullFacts;
   private final StaticFieldResolver staticFieldResolver;

   public BytecodeAnalyzer(final ClassInfo classInfo) {
      this(classInfo, new StaticFieldResolver());
   }

   public BytecodeAnalyzer(final ClassInfo classInfo, final StaticFieldResolver staticFieldResolver) {
      this(classInfo, new MethodSummaryResolver(staticFieldResolver));
   }

   public BytecodeAnalyzer(final ClassInfo classInfo, final MethodSummaryResolver methodSummaryResolver) {
      this(readClass(classInfo), methodSummaryResolver);
   }

   private BytecodeAnalyzer(final ClassNode classNode, final MethodSummaryResolver methodSummaryResolver) {
      this.classNode = classNode;
      staticFieldResolver = methodSummaryResolver.staticFieldResolver;
      this.methodSummaryResolver = methodSummaryResolver;
      staticFieldResolver.register(classNode);
   }

   private static ClassNode readClass(final ClassInfo classInfo) {
      try (var classFileResource = classInfo.getResource()) {
         if (classFileResource == null)
            throw new IOException("Class resource not found: " + classInfo);
         try (var is = classFileResource.open()) {
            final var classReader = new ClassReader(is);
            final var classNode = new ClassNode();
            classReader.accept(classNode, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return classNode;
         }
      } catch (final IOException ex) {
         throw new UncheckedIOException("Failed to read class resource: " + classInfo, ex);
      }
   }

   private static String fieldKey(final String name, final String descriptor) {
      return name + '\0' + descriptor;
   }

   private static boolean isWithinAnalysisBudget(final MethodNode method) {
      final int instructionCount = method.instructions.size();
      final long frameValues = instructionCount * Math.max(1L, (long) method.maxLocals + method.maxStack);
      /* ASM expands each protected range into handler edges. The full product is a safe upper bound that is cheap to
       * calculate even when a crafted class contains many overlapping exception-table entries. */
      final long exceptionEdges = (long) instructionCount * method.tryCatchBlocks.size();
      return instructionCount <= MAX_ANALYSIS_INSTRUCTIONS && frameValues <= MAX_ANALYSIS_FRAME_VALUES
            && exceptionEdges <= MAX_ANALYSIS_EXCEPTION_EDGES;
   }

   private static void logAnalysisBudgetExceeded(final @Nullable String owner, final MethodNode method) {
      System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
         "Skipping optional bytecode analysis of {0}.{1}{2}: instructions={3}, maxLocals={4}, maxStack={5}, handlers={6}", owner,
         method.name, method.desc, method.instructions.size(), method.maxLocals, method.maxStack, method.tryCatchBlocks.size());
   }

   private static boolean mayMutatePrivateFields(final int opcode) {
      return opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKESPECIAL || opcode == Opcodes.INVOKESTATIC
            || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKEDYNAMIC;
   }

   private static boolean mergeKnownNonNullFields(final Map<Integer, Set<String>> knownFieldsByInstruction,
         final Deque<Integer> pendingInstructions, final int instructionIndex, final Set<String> incomingFields,
         final FieldFactBudget budget) {
      final Set<String> existingFields = knownFieldsByInstruction.get(instructionIndex);
      if (existingFields == null) {
         if (!budget.tryConsume(incomingFields.size()))
            return false;
         knownFieldsByInstruction.put(instructionIndex, Set.copyOf(incomingFields));
         pendingInstructions.add(instructionIndex);
         return true;
      }

      if (!budget.tryConsume(existingFields.size()))
         return false;
      final Set<String> mergedFields = new HashSet<>(existingFields);
      // A field is a must-fact at a join only when every predecessor proves it non-null.
      mergedFields.retainAll(incomingFields);
      if (!mergedFields.equals(existingFields)) {
         if (!budget.tryConsume(mergedFields.size()))
            return false;
         knownFieldsByInstruction.put(instructionIndex, Set.copyOf(mergedFields));
         pendingInstructions.add(instructionIndex);
      }
      return true;
   }

   private Map<AbstractInsnNode, Set<String>> fieldFactBudgetExceeded(final MethodNode methodNode) {
      System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
         "Skipping private-field fact refinement of {0}.{1}{2}: cumulative field-fact work exceeded {3}", classNode.name, methodNode.name,
         methodNode.desc, MAX_FIELD_FACT_ANALYSIS_WORK);
      // Empty facts preserve soundness in the following value pass; only the optional cached-field proof is lost.
      return Map.of();
   }

   @SuppressWarnings("null")
   private Map<AbstractInsnNode, Set<String>> determineKnownNonNullFieldsAtInstructions(final MethodNode methodNode,
         final AbstractInsnNode[] instructions, final Map<AbstractInsnNode, Set<String>> nullBranchRequirements,
         final Map<AbstractInsnNode, String> testedPrivateFields, final FieldFactBudget budget) throws AnalyzerException {
      final var controlFlow = new ControlFlowAnalyzer(instructions.length);
      controlFlow.analyze(classNode.name, methodNode);

      final Map<AbstractInsnNode, Integer> instructionIndexes = new IdentityHashMap<>();
      for (int i = 0; i < instructions.length; i++) {
         instructionIndexes.put(instructions[i], i);
      }

      final Map<Integer, Set<String>> knownFieldsByInstruction = new HashMap<>();
      final Deque<Integer> pendingInstructions = new ArrayDeque<>();
      knownFieldsByInstruction.put(0, Set.of());
      pendingInstructions.add(0);
      while (!pendingInstructions.isEmpty()) {
         final int instructionIndex = pendingInstructions.removeFirst();
         final AbstractInsnNode instruction = instructions[instructionIndex];
         final Set<String> incomingFields = Objects.requireNonNull(knownFieldsByInstruction.get(instructionIndex));
         if (!budget.tryConsume(incomingFields.size()))
            return fieldFactBudgetExceeded(methodNode);
         final Set<String> outgoingFields = new HashSet<>(incomingFields);
         final int opcode = instruction.getOpcode();
         if (mayMutatePrivateFields(opcode)) {
            outgoingFields.clear();
         } else if (opcode == Opcodes.PUTFIELD && instruction instanceof FieldInsnNode) {
            final FieldInsnNode field = (FieldInsnNode) instruction;
            outgoingFields.remove(fieldKey(field.name, field.desc));
         }

         final Set<String> requiredFields = nullBranchRequirements.getOrDefault(instruction, Set.of());
         final String testedPrivateField = testedPrivateFields.get(instruction);
         final @Nullable Integer jumpTargetIndex = instruction instanceof JumpInsnNode ? instructionIndexes.get(
            ((JumpInsnNode) instruction).label) : null;
         for (final int successorIndex : controlFlow.normalSuccessors.get(instructionIndex)) {
            if (!budget.tryConsume(outgoingFields.size()))
               return fieldFactBudgetExceeded(methodNode);
            final Set<String> edgeFields = new HashSet<>(outgoingFields);
            if (jumpTargetIndex != null) {
               final boolean isNullEdge = opcode == Opcodes.IFNULL ? successorIndex == jumpTargetIndex : successorIndex != jumpTargetIndex;
               // Ordinary field tests establish the non-null edge; sentinel requirements belong to the null edge.
               if (isNullEdge) {
                  if (testedPrivateField != null) {
                     edgeFields.remove(testedPrivateField);
                  }
                  if (!budget.tryConsume(requiredFields.size()))
                     return fieldFactBudgetExceeded(methodNode);
                  edgeFields.addAll(requiredFields);
               } else if (testedPrivateField != null) {
                  edgeFields.add(testedPrivateField);
               }
            }
            if (!mergeKnownNonNullFields(knownFieldsByInstruction, pendingInstructions, successorIndex, edgeFields, budget))
               return fieldFactBudgetExceeded(methodNode);
         }
         for (final int successorIndex : controlFlow.exceptionSuccessors.get(instructionIndex)) {
            // A handler may run before the throwing instruction completes, so no normal-edge cache fact crosses it.
            if (!mergeKnownNonNullFields(knownFieldsByInstruction, pendingInstructions, successorIndex, Set.of(), budget))
               return fieldFactBudgetExceeded(methodNode);
         }
      }

      final Map<AbstractInsnNode, Set<String>> result = new IdentityHashMap<>();
      knownFieldsByInstruction.forEach((instructionIndex, fields) -> result.put(instructions[instructionIndex], fields));
      return result;
   }

   @SuppressWarnings("null")
   private Map<AbstractInsnNode, Set<String>> retainPreservedNullBranchRequirements(final MethodNode methodNode,
         final AbstractInsnNode[] instructions, final Map<AbstractInsnNode, Set<String>> candidateRequirements,
         final Map<AbstractInsnNode, FlowValue> testedValues, final FieldFactBudget budget) throws AnalyzerException {
      if (candidateRequirements.isEmpty())
         return candidateRequirements;

      final var controlFlow = new ControlFlowAnalyzer(instructions.length);
      controlFlow.analyze(classNode.name, methodNode);
      final Map<AbstractInsnNode, Integer> instructionIndexes = new IdentityHashMap<>();
      final List<Set<Integer>> predecessors = new ArrayList<>(instructions.length);
      for (int i = 0; i < instructions.length; i++) {
         if (!budget.tryConsume(1))
            return fieldFactBudgetExceeded(methodNode);
         instructionIndexes.put(instructions[i], i);
         predecessors.add(new HashSet<>());
      }
      for (int i = 0; i < instructions.length; i++) {
         for (final int successor : controlFlow.normalSuccessors.get(i)) {
            if (!budget.tryConsume(1))
               return fieldFactBudgetExceeded(methodNode);
            predecessors.get(successor).add(i);
         }
         for (final int successor : controlFlow.exceptionSuccessors.get(i)) {
            if (!budget.tryConsume(1))
               return fieldFactBudgetExceeded(methodNode);
            predecessors.get(successor).add(i);
         }
      }

      final Map<AbstractInsnNode, Set<String>> preservedRequirements = new IdentityHashMap<>();
      for (final Map.Entry<AbstractInsnNode, Set<String>> entry : candidateRequirements.entrySet()) {
         final AbstractInsnNode branch = entry.getKey();
         final Integer branchIndex = instructionIndexes.get(branch);
         final FlowValue testedValue = testedValues.get(branch);
         if (branchIndex == null || testedValue == null) {
            continue;
         }

         // Ignore side paths that cannot carry the sentinel to this test; mutations there do not weaken its field fact.
         final Set<Integer> instructionsReachingBranch = new HashSet<>();
         final Deque<Integer> pendingPredecessors = new ArrayDeque<>();
         instructionsReachingBranch.add(branchIndex);
         pendingPredecessors.add(branchIndex);
         while (!pendingPredecessors.isEmpty()) {
            for (final int predecessor : predecessors.get(pendingPredecessors.removeFirst())) {
               if (!budget.tryConsume(1))
                  return fieldFactBudgetExceeded(methodNode);
               if (instructionsReachingBranch.add(predecessor)) {
                  pendingPredecessors.add(predecessor);
               }
            }
         }

         final Set<String> preservedFields = new HashSet<>();
         for (final String field : entry.getValue()) {
            boolean foundNullSource = false;
            boolean invalidated = false;
            final Set<Integer> visited = new HashSet<>();
            final Deque<Integer> pendingInstructions = new ArrayDeque<>();
            for (final AbstractInsnNode source : testedValue.insns) {
               if (!budget.tryConsume(1))
                  return fieldFactBudgetExceeded(methodNode);
               if (source.getOpcode() == Opcodes.ACONST_NULL) {
                  foundNullSource = true;
                  final Integer sourceIndex = instructionIndexes.get(source);
                  if (sourceIndex == null || !instructionsReachingBranch.contains(sourceIndex)) {
                     invalidated = true;
                     break;
                  }
                  if (visited.add(sourceIndex)) {
                     pendingInstructions.add(sourceIndex);
                  }
               }
            }
            while (!invalidated && !pendingInstructions.isEmpty()) {
               final int instructionIndex = pendingInstructions.removeFirst();
               if (instructionIndex == branchIndex) {
                  continue;
               }

               final AbstractInsnNode instruction = instructions[instructionIndex];
               final int opcode = instruction.getOpcode();
               if (mayMutatePrivateFields(opcode) || opcode == Opcodes.PUTFIELD && instruction instanceof FieldInsnNode && field.equals(
                  fieldKey(((FieldInsnNode) instruction).name, ((FieldInsnNode) instruction).desc))) {
                  /* The sentinel encodes a field fact from the path where ACONST_NULL was executed. Do not resurrect
                   * that fact if a call or write could have changed the field before the sentinel is tested. */
                  invalidated = true;
                  break;
               }

               for (final int successor : controlFlow.normalSuccessors.get(instructionIndex)) {
                  if (!budget.tryConsume(1))
                     return fieldFactBudgetExceeded(methodNode);
                  if (instructionsReachingBranch.contains(successor) && visited.add(successor)) {
                     pendingInstructions.add(successor);
                  }
               }
               for (final int successor : controlFlow.exceptionSuccessors.get(instructionIndex)) {
                  if (!budget.tryConsume(1))
                     return fieldFactBudgetExceeded(methodNode);
                  if (instructionsReachingBranch.contains(successor) && visited.add(successor)) {
                     pendingInstructions.add(successor);
                  }
               }
            }
            if (foundNullSource && !invalidated) {
               preservedFields.add(field);
            }
         }
         if (!preservedFields.isEmpty()) {
            preservedRequirements.put(branch, Set.copyOf(preservedFields));
         }
      }
      return preservedRequirements;
   }

   private boolean isDefinitelyNonNullStaticField(final String owner, final String name, final String descriptor) {
      return staticFieldResolver.isDefinitelyNonNull(owner, name, descriptor);
   }

   private static boolean isKnownNonNullMethod(final int opcode, final String clazz, final String methodName, final String descriptor) {
      // Share this exact contract with static-field provenance so both analysis passes classify wrapper factories alike.
      if (isPrimitiveWrapperValueOf(opcode, clazz, methodName, descriptor))
         return true;

      // Array clone is JVM-defined and cannot dispatch to a user override. Limit toString() trust to final JDK builders;
      // an arbitrary override may legally return null. String.valueOf(Object) is unsafe for the same reason because it
      // returns the virtual toString() result verbatim.
      // CHECKSTYLE:IGNORE .* FOR NEXT 8 LINES
      return methodName.equals("<init>") //
            || clazz.startsWith("[") && methodName.equals("clone") && descriptor.equals("()Ljava/lang/Object;") //
            || (clazz.equals("java/lang/StringBuilder") || clazz.equals("java/lang/StringBuffer")) && methodName.equals("toString")
                  && descriptor.equals("()Ljava/lang/String;") //
            || clazz.equals("java/lang/StringBuilder") && (methodName.startsWith("append") || methodName.startsWith("insert")) //
            || clazz.equals("java/lang/invoke/StringConcatFactory") && methodName.startsWith("makeConcat") //
            // Reflective array allocation either throws or returns the newly allocated array; a normal result is never null.
            || clazz.equals("java/lang/reflect/Array") && methodName.equals("newInstance") && ("(Ljava/lang/Class;I)Ljava/lang/Object;"
               .equals(descriptor) || "(Ljava/lang/Class;[I)Ljava/lang/Object;".equals(descriptor));
   }

   private @Nullable List<ClassNode> resolveNestClasses() {
      final ClassNode nestHost;
      if (classNode.nestHostClass == null) {
         nestHost = classNode;
      } else {
         nestHost = methodSummaryResolver.resolveClass(classNode.nestHostClass);
         if (nestHost == null)
            return null;
      }

      final var result = new ArrayList<ClassNode>();
      result.add(nestHost);
      boolean containsTargetClass = nestHost.name.equals(classNode.name);
      @SuppressWarnings("null")
      final List<String> nestMembers = nestHost.nestMembers == null ? List.of() : nestHost.nestMembers;
      for (final String nestMemberName : nestMembers) {
         if (nestMemberName.equals(nestHost.name)) {
            continue;
         }
         final ClassNode nestMember = nestMemberName.equals(classNode.name) //
               ? classNode //
               : methodSummaryResolver.resolveClass(nestMemberName);
         if (nestMember == null)
            return null;
         containsTargetClass = containsTargetClass || nestMemberName.equals(classNode.name);
         result.add(nestMember);
      }
      // Missing or inconsistent nest metadata cannot establish that every class allowed to write the private field was inspected.
      return containsTargetClass ? result : null;
   }

   @SuppressWarnings("null")
   private Set<String> determinePrivateFieldsPreservingNonNullFacts() {
      final Set<String> immutableFields = new HashSet<>();
      final Set<String> mutableCandidates = new HashSet<>();
      for (final FieldNode field : classNode.fields) {
         if ((field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) != Opcodes.ACC_PRIVATE || !isReferenceType(field.desc)) {
            continue;
         }
         final String key = fieldKey(field.name, field.desc);
         if ((field.access & Opcodes.ACC_FINAL) == Opcodes.ACC_FINAL) {
            // The fact starts only after a read proved the value non-null; an ordinary final field cannot change afterwards.
            immutableFields.add(key);
         } else {
            mutableCandidates.add(key);
         }
      }
      if (mutableCandidates.isEmpty())
         return immutableFields;

      /* This is deliberately a closed-classpath proof. Reflective, Unsafe, and runtime-defined hidden-nestmate writes
       * are outside the bytecode contracts this analyzer can establish. */
      final List<ClassNode> nestClasses = resolveNestClasses();
      if (nestClasses == null)
         // Private access extends to nestmates. Without the complete nest, mutable fields cannot be proven monotonic.
         return immutableFields;

      for (final ClassNode nestClass : nestClasses) {
         for (final MethodNode method : nestClass.methods) {
            if (mutableCandidates.isEmpty())
               return immutableFields;

            if ((method.access & Opcodes.ACC_NATIVE) != 0)
               /* JNI can mutate any private instance field without a PUTFIELD instruction. With no body to inspect,
                * none of the nest's mutable candidates has a closed-bytecode preservation proof. */
               return immutableFields;

            final AbstractInsnNode[] instructions = method.instructions.toArray();
            final Set<String> fieldsWrittenByMethod = new HashSet<>();
            for (final AbstractInsnNode instruction : instructions) {
               if (instruction.getOpcode() == Opcodes.PUTFIELD) {
                  final FieldInsnNode field = (FieldInsnNode) instruction;
                  if (field.owner.equals(classNode.name)) {
                     final String key = fieldKey(field.name, field.desc);
                     if (mutableCandidates.contains(key)) {
                        fieldsWrittenByMethod.add(key);
                     }
                  }
               }
            }
            if (fieldsWrittenByMethod.isEmpty()) {
               continue;
            }

            if (!isWithinAnalysisBudget(method)) {
               /* Without analyzing every write, none of the affected mutable fields has the closed-bytecode
                * preservation proof required to carry a non-null fact across reads. */
               mutableCandidates.removeAll(fieldsWrittenByMethod);
               logAnalysisBudgetExceeded(nestClass.name, method);
               continue;
            }

            try {
               /* This pass must not consume the private-field facts it is proving. Branch refinement is still needed:
                * cached-return bytecode commonly stores a merged sentinel only on its proven non-null edge. */
               final Frame<SourceValue>[] frames = new FlowAnalyzer(new FlowInterpreter(false), Map.of()).analyze(nestClass.name, method);
               for (int i = 0; i < instructions.length; i++) {
                  if (instructions[i].getOpcode() != Opcodes.PUTFIELD) {
                     continue;
                  }
                  final FieldInsnNode field = (FieldInsnNode) instructions[i];
                  final String key = fieldKey(field.name, field.desc);
                  if (!field.owner.equals(classNode.name) || !mutableCandidates.contains(key)) {
                     continue;
                  }

                  final Frame<SourceValue> frame = frames[i];
                  if (frame != null && (frame.getStackSize() == 0 || !(frame.getStack(frame.getStackSize() - 1) instanceof FlowValue)
                        || ((FlowValue) frame.getStack(frame.getStackSize() - 1)).nullness != FlowNullness.NEVER_NULL)) {
                     mutableCandidates.remove(key);
                  }
               }
            } catch (final AnalyzerException ex) {
               // Optional stability evidence must fail closed for every target field written by unsupported bytecode.
               mutableCandidates.removeAll(fieldsWrittenByMethod);
               System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
                  "Failed to analyze private field writes in " + nestClass.name + "." + method.name + method.desc, ex);
            }
         }
      }

      immutableFields.addAll(mutableCandidates);
      return immutableFields;
   }

   @SuppressWarnings("null")
   private final class FlowInterpreter extends SourceInterpreter {
      private final boolean trackPrivateFieldFacts;

      FlowInterpreter() {
         this(true);
      }

      FlowInterpreter(final boolean trackPrivateFieldFacts) {
         // SourceInterpreter's public constructor rejects subclasses; the protected API-level constructor is the extension point.
         super(Opcodes.ASM9);
         this.trackPrivateFieldFacts = trackPrivateFieldFacts;
      }

      private FlowValue createdValue(final SourceValue source, final FlowNullness nullness) {
         final boolean mayHaveNonConstantNullPath = nullness == FlowNullness.UNKNOWN || nullness == FlowNullness.MAY_INCLUDE_NULL;
         return new FlowValue(source, Set.of(), nullness, false, mayHaveNonConstantNullPath, null, Set.of());
      }

      private @Nullable String determinePrivateThisFieldKey(final FieldInsnNode field, final FlowValue receiver) {
         if (!trackPrivateFieldFacts || !receiver.insns.isEmpty() || !field.owner.equals(classNode.name) || receiver.parameterLocalSlots
            .size() != 1 || !receiver.parameterLocalSlots.contains(0))
            return null;

         final String key = fieldKey(field.name, field.desc);
         /* The initial this value has no producer instruction. Requiring that property prevents a merge of this with a
          * fresh allocation from inheriting slot 0's identity merely because parameter provenance uses a union. */
         /* Private identity is not enough: another invocation on the same object may run concurrently. Carry the fact
          * across a second read only when finality or all ordinary nest writes preserve non-nullness. */
         return getPrivateFieldsPreservingNonNullFacts().contains(key) ? key : null;
      }

      private Set<String> getPrivateFieldsPreservingNonNullFacts() {
         Set<String> result = privateFieldsPreservingNonNullFacts;
         if (result == null) {
            result = Set.copyOf(determinePrivateFieldsPreservingNonNullFacts());
            privateFieldsPreservingNonNullFacts = result;
         }
         return result;
      }

      private FlowNullness nullnessForType(final @Nullable Type type) {
         if (type == null)
            return FlowNullness.UNKNOWN;
         final int sort = type.getSort();
         return sort == Type.OBJECT || sort == Type.ARRAY ? FlowNullness.UNKNOWN : FlowNullness.NEVER_NULL;
      }

      @Override
      public @Nullable SourceValue newValue(final @Nullable Type type) {
         final SourceValue source = super.newValue(type);
         return source == null ? null : createdValue(source, nullnessForType(type));
      }

      @Override
      @NonNullByDefault({})
      public SourceValue newParameterValue(final boolean isInstanceMethod, final int local, final Type type) {
         final FlowValue value = (FlowValue) Objects.requireNonNull(newValue(type));
         final FlowNullness nullness = isInstanceMethod && local == 0 ? FlowNullness.NEVER_NULL : value.nullness;
         final boolean mayHaveNonConstantNullPath = nullness != FlowNullness.NEVER_NULL && value.mayHaveNonConstantNullPath;
         /* Entry values have no producer instruction. Retain their local slots so two parameters with otherwise equal
          * abstract values do not become aliases when their paths merge. */
         return new FlowValue(value, Set.of(local), nullness, false, mayHaveNonConstantNullPath, null, Set.of());
      }

      @Override
      @NonNullByDefault({})
      public SourceValue newExceptionValue(final TryCatchBlockNode tryCatchBlockNode, final Frame<SourceValue> handlerFrame,
            final Type exceptionType) {
         // The JVM creates the caught exception reference; a handler never starts with null on its operand stack.
         return new FlowValue(new SourceValue(exceptionType.getSize(), Set.of(tryCatchBlockNode.handler)), Set.of(),
            FlowNullness.NEVER_NULL, false, false, null, Set.of());
      }

      @Override
      public SourceValue newOperation(final @NonNullByDefault({}) AbstractInsnNode instruction) {
         final SourceValue source = super.newOperation(instruction);
         switch (instruction.getOpcode()) {
            case Opcodes.ACONST_NULL:
               return new FlowValue(source, Set.of(), FlowNullness.DEFINITELY_NULL, true, false, null, Set.of());
            case Opcodes.NEW:
               return createdValue(source, FlowNullness.NEVER_NULL);
            case Opcodes.LDC: {
               final Object constant = ((LdcInsnNode) instruction).cst;
               if (constant instanceof ConstantDynamic)
                  return createdValue(source, nullnessForType(Type.getType(((ConstantDynamic) constant).getDescriptor())));
               return createdValue(source, FlowNullness.NEVER_NULL);
            }
            case Opcodes.GETSTATIC: {
               final FieldInsnNode field = (FieldInsnNode) instruction;
               final Type fieldType = Type.getType(field.desc);
               if (!isReferenceType(field.desc) || isDefinitelyNonNullStaticField(field.owner, field.name, field.desc))
                  return createdValue(source, FlowNullness.NEVER_NULL);
               return createdValue(source, nullnessForType(fieldType));
            }
            default:
               // All remaining new-operation values are primitive constants or a legacy JSR return address.
               return createdValue(source, FlowNullness.NEVER_NULL);
         }
      }

      @Override
      @NonNullByDefault({})
      public SourceValue copyOperation(final AbstractInsnNode instruction, final SourceValue value) {
         // Loads, stores, and DUP operations copy the same runtime value, so preserve object identity for branch refinement.
         return value;
      }

      @Override
      @NonNullByDefault({})
      public SourceValue unaryOperation(final AbstractInsnNode instruction, final SourceValue value) {
         if (instruction.getOpcode() == Opcodes.CHECKCAST)
            // A successful cast preserves both the runtime reference and its nullness.
            return value;

         final SourceValue source = super.unaryOperation(instruction, value);
         switch (instruction.getOpcode()) {
            case Opcodes.ANEWARRAY:
            case Opcodes.NEWARRAY:
               return createdValue(source, FlowNullness.NEVER_NULL);
            case Opcodes.GETFIELD: {
               final FieldInsnNode field = (FieldInsnNode) instruction;
               final FlowValue fieldValue = createdValue(source, nullnessForType(Type.getType(field.desc)));
               final String privateFieldKey = determinePrivateThisFieldKey(field, (FlowValue) value);
               return privateFieldKey == null ? fieldValue : fieldValue.withPrivateThisFieldKey(privateFieldKey);
            }
            default:
               // Other unary operations either produce primitives or have no result consumed by later instructions.
               return createdValue(source, FlowNullness.NEVER_NULL);
         }
      }

      @Override
      @NonNullByDefault({})
      public SourceValue binaryOperation(final AbstractInsnNode instruction, final SourceValue first, final SourceValue second) {
         final SourceValue source = super.binaryOperation(instruction, first, second);
         return createdValue(source, instruction.getOpcode() == Opcodes.AALOAD ? FlowNullness.UNKNOWN : FlowNullness.NEVER_NULL);
      }

      @Override
      @NonNullByDefault({})
      public SourceValue ternaryOperation(final AbstractInsnNode instruction, final SourceValue first, final SourceValue second,
            final SourceValue third) {
         return createdValue(super.ternaryOperation(instruction, first, second, third), FlowNullness.UNKNOWN);
      }

      @Override
      @NonNullByDefault({})
      public SourceValue naryOperation(final AbstractInsnNode instruction, final List<? extends SourceValue> values) {
         final SourceValue source = super.naryOperation(instruction, values);
         if (instruction.getOpcode() == Opcodes.MULTIANEWARRAY)
            return createdValue(source, FlowNullness.NEVER_NULL);

         final Type returnType;
         final boolean knownNonNull;
         if (instruction instanceof MethodInsnNode) {
            final MethodInsnNode method = (MethodInsnNode) instruction;
            returnType = Type.getReturnType(method.desc);
            knownNonNull = isKnownNonNullMethod(method.getOpcode(), method.owner, method.name, method.desc);
         } else {
            final InvokeDynamicInsnNode method = (InvokeDynamicInsnNode) instruction;
            returnType = Type.getReturnType(method.desc);
            knownNonNull = isKnownNonNullMethod(method.getOpcode(), method.bsm.getOwner(), method.name, method.desc);
         }
         return createdValue(source, knownNonNull ? FlowNullness.NEVER_NULL : nullnessForType(returnType));
      }

      @Override
      @NonNullByDefault({})
      public void returnOperation(final AbstractInsnNode instruction, final SourceValue value, final SourceValue expected) {
         // This analysis classifies values reaching ARETURN; descriptor compatibility remains ASM's responsibility.
      }

      @Override
      @NonNullByDefault({})
      public SourceValue merge(final SourceValue first, final SourceValue second) {
         final FlowValue firstFlowValue = (FlowValue) first;
         final FlowValue secondFlowValue = (FlowValue) second;
         final FlowNullness mergedNullness = FlowNullness.merge(firstFlowValue.nullness, secondFlowValue.nullness);

         final Set<AbstractInsnNode> mergedSources = new HashSet<>(first.insns);
         mergedSources.addAll(second.insns);
         final Set<Integer> mergedParameterSlots = new HashSet<>(firstFlowValue.parameterLocalSlots);
         mergedParameterSlots.addAll(secondFlowValue.parameterLocalSlots);
         final int mergedSize = Math.min(first.size, second.size);
         final boolean mergedNullConstantPath = firstFlowValue.nullConstantPath || secondFlowValue.nullConstantPath;
         final boolean mergedNonConstantNullPath = firstFlowValue.mayHaveNonConstantNullPath || secondFlowValue.mayHaveNonConstantNullPath;
         final String mergedPrivateFieldKey = Objects.equals(firstFlowValue.privateThisFieldKey, secondFlowValue.privateThisFieldKey)
               ? firstFlowValue.privateThisFieldKey
               : null;
         final Set<String> mergedRequiredFields;
         if (firstFlowValue.nullConstantPath && secondFlowValue.nullConstantPath) {
            mergedRequiredFields = new HashSet<>(firstFlowValue.requiredNonNullFieldsForNullConstantPath);
            mergedRequiredFields.retainAll(secondFlowValue.requiredNonNullFieldsForNullConstantPath);
         } else if (firstFlowValue.nullConstantPath) {
            mergedRequiredFields = firstFlowValue.requiredNonNullFieldsForNullConstantPath;
         } else if (secondFlowValue.nullConstantPath) {
            mergedRequiredFields = secondFlowValue.requiredNonNullFieldsForNullConstantPath;
         } else {
            mergedRequiredFields = Set.of();
         }

         if (mergedSize == first.size && mergedNullness == firstFlowValue.nullness
               && mergedNullConstantPath == firstFlowValue.nullConstantPath
               && mergedNonConstantNullPath == firstFlowValue.mayHaveNonConstantNullPath && Objects.equals(mergedPrivateFieldKey,
                  firstFlowValue.privateThisFieldKey) && mergedSources.equals(first.insns) && mergedParameterSlots.equals(
                     firstFlowValue.parameterLocalSlots) && mergedRequiredFields.equals(
                        firstFlowValue.requiredNonNullFieldsForNullConstantPath))
            return first;
         if (mergedSize == second.size && mergedNullness == secondFlowValue.nullness
               && mergedNullConstantPath == secondFlowValue.nullConstantPath
               && mergedNonConstantNullPath == secondFlowValue.mayHaveNonConstantNullPath && Objects.equals(mergedPrivateFieldKey,
                  secondFlowValue.privateThisFieldKey) && mergedSources.equals(second.insns) && mergedParameterSlots.equals(
                     secondFlowValue.parameterLocalSlots) && mergedRequiredFields.equals(
                        secondFlowValue.requiredNonNullFieldsForNullConstantPath))
            return second;
         return new FlowValue(new SourceValue(mergedSize, mergedSources), mergedParameterSlots, mergedNullness, mergedNullConstantPath,
            mergedNonConstantNullPath, mergedPrivateFieldKey, mergedRequiredFields);
      }
   }

   private static boolean isReferenceType(final String descriptor) {
      final int sort = Type.getType(descriptor).getSort();
      return sort == Type.OBJECT || sort == Type.ARRAY;
   }

   @SuppressWarnings("null")
   private static boolean isPrimitiveWrapperValueOf(final MethodInsnNode method) {
      return isPrimitiveWrapperValueOf(method.getOpcode(), method.owner, method.name, method.desc);
   }

   private static boolean isPrimitiveWrapperValueOf(final int opcode, final String owner, final String methodName,
         final String descriptor) {
      if (opcode != Opcodes.INVOKESTATIC || !"valueOf".equals(methodName))
         return false;

      /* Exact JDK owners and primitive-input descriptors matter here: these factories cannot return null, even when
       * their implementation reads a cached object from an array. An application method named valueOf may return null. */
      switch (owner) {
         case "java/lang/Boolean":
            return "(Z)Ljava/lang/Boolean;".equals(descriptor);
         case "java/lang/Byte":
            return "(B)Ljava/lang/Byte;".equals(descriptor);
         case "java/lang/Character":
            return "(C)Ljava/lang/Character;".equals(descriptor);
         case "java/lang/Double":
            return "(D)Ljava/lang/Double;".equals(descriptor);
         case "java/lang/Float":
            return "(F)Ljava/lang/Float;".equals(descriptor);
         case "java/lang/Integer":
            return "(I)Ljava/lang/Integer;".equals(descriptor);
         case "java/lang/Long":
            return "(J)Ljava/lang/Long;".equals(descriptor);
         case "java/lang/Short":
            return "(S)Ljava/lang/Short;".equals(descriptor);
         default:
            return false;
      }
   }

   private static @Nullable Set<AbstractInsnNode> determineNonNullSourceDependencies(final AbstractInsnNode source,
         final Frame<SourceValue>[] frames, final Map<AbstractInsnNode, Integer> instructionIndexes) {
      // An empty set is a proven leaf; null means that this source cannot be proven non-null.
      switch (source.getOpcode()) {
         case Opcodes.NEW:
         case Opcodes.NEWARRAY:
         case Opcodes.ANEWARRAY:
         case Opcodes.MULTIANEWARRAY:
            return Set.of();
         case Opcodes.LDC:
            // A constant-dynamic bootstrap may legally produce null; ordinary reference constants cannot.
            return ((LdcInsnNode) source).cst instanceof ConstantDynamic ? null : Set.of();
         case Opcodes.INVOKESTATIC:
            return isPrimitiveWrapperValueOf((MethodInsnNode) source) ? Set.of() : null;
         case Opcodes.GETSTATIC:
            // A field read inside <clinit> may occur before that field's assignment, even if its final value is non-null.
            return null;
         default:
            return determineForwardedSourceDependencies(source, frames, instructionIndexes);
      }
   }

   private static @Nullable Set<AbstractInsnNode> determineForwardedSourceDependencies(final AbstractInsnNode source,
         final Frame<SourceValue>[] frames, final Map<AbstractInsnNode, Integer> instructionIndexes) {
      switch (source.getOpcode()) {
         case Opcodes.ALOAD: {
            final Integer instructionIndex = instructionIndexes.get(source);
            if (instructionIndex == null)
               return null;
            @SuppressWarnings("null")
            final Set<AbstractInsnNode> dependencies = frames[instructionIndex].getLocal(((VarInsnNode) source).var).insns;
            // A forwarding instruction cannot turn missing provenance into a proven leaf.
            return dependencies.isEmpty() ? null : dependencies;
         }
         case Opcodes.ASTORE:
         case Opcodes.CHECKCAST:
         case Opcodes.DUP: {
            /* SourceInterpreter records these reference-preserving operations as new producers. Follow their input
             * so javac's NEW/DUP/constructor sequence and local temporary variables retain the original proof. */
            final Integer instructionIndex = instructionIndexes.get(source);
            if (instructionIndex == null)
               return null;
            final Frame<SourceValue> frame = frames[instructionIndex];
            if (frame.getStackSize() == 0)
               return null;
            @SuppressWarnings("null")
            final Set<AbstractInsnNode> dependencies = frame.getStack(frame.getStackSize() - 1).insns;
            return dependencies.isEmpty() ? null : dependencies;
         }
         default:
            return null;
      }
   }

   @SuppressWarnings("null")
   private static boolean areAllSourcesProven(final SourceValue value, final SourceDependencyResolver dependencyResolver) {
      // SourceInterpreter unions producers at control-flow joins. Every producer must therefore satisfy the selected proof.
      if (value.insns.isEmpty())
         return false;

      /* FALSE means that a source is on the active DFS path; TRUE caches a completed proof. Keeping this state outside
       * the Java call stack handles arbitrarily deep valid bytecode while still rejecting provenance cycles. */
      final var sourceStates = new IdentityHashMap<AbstractInsnNode, Boolean>();
      for (final AbstractInsnNode rootSource : value.insns) {
         if (Boolean.TRUE.equals(sourceStates.get(rootSource))) {
            continue;
         }

         final @Nullable Set<AbstractInsnNode> rootDependencies = dependencyResolver.determineDependencies(rootSource);
         if (rootDependencies == null)
            return false;

         final Deque<SourceProofFrame> pendingSources = new ArrayDeque<>();
         sourceStates.put(rootSource, Boolean.FALSE);
         pendingSources.push(new SourceProofFrame(rootSource, rootDependencies));
         while (!pendingSources.isEmpty()) {
            final SourceProofFrame current = Objects.requireNonNull(pendingSources.peek());
            if (!current.dependencies.hasNext()) {
               sourceStates.put(current.source, Boolean.TRUE);
               pendingSources.pop();
               continue;
            }

            final AbstractInsnNode dependency = current.dependencies.next();
            final Boolean dependencyState = sourceStates.get(dependency);
            if (Boolean.TRUE.equals(dependencyState)) {
               continue;
            }
            if (Boolean.FALSE.equals(dependencyState))
               return false;

            final @Nullable Set<AbstractInsnNode> dependencies = dependencyResolver.determineDependencies(dependency);
            if (dependencies == null)
               return false;
            sourceStates.put(dependency, Boolean.FALSE);
            pendingSources.push(new SourceProofFrame(dependency, dependencies));
         }
      }
      return true;
   }

   @SuppressWarnings("null")
   private static DependencySummary determineDependencies(final SourceValue value, final DependencyExpansionResolver expansionResolver) {
      if (value.insns.isEmpty())
         return DependencySummary.UNKNOWN;

      /* Bytecode can contain arbitrarily long chains of aliases and casts. Evaluate the provenance graph explicitly so
       * valid class files cannot consume the Java call stack; the active set still rejects cyclic provenance conservatively. */
      final Map<AbstractInsnNode, DependencySummary> completed = new IdentityHashMap<>();
      final Set<AbstractInsnNode> active = Collections.newSetFromMap(new IdentityHashMap<>());
      DependencySummary result = DependencySummary.NON_NULL;
      for (final AbstractInsnNode rootSource : value.insns) {
         DependencySummary rootResult = completed.get(rootSource);
         if (rootResult == null) {
            final DependencyExpansion rootExpansion = expansionResolver.determineExpansion(rootSource);
            if (rootExpansion.dependencies == null) {
               rootResult = rootExpansion.summary;
               completed.put(rootSource, rootResult);
            } else {
               final Set<AbstractInsnNode> rootDependencies = Objects.requireNonNull(rootExpansion.dependencies);
               final Deque<DependencyFrame> pendingSources = new ArrayDeque<>();
               active.add(rootSource);
               pendingSources.push(new DependencyFrame(rootSource, rootDependencies));
               while (!pendingSources.isEmpty()) {
                  final DependencyFrame current = Objects.requireNonNull(pendingSources.peek());
                  if (!current.dependencies.hasNext()) {
                     pendingSources.pop();
                     active.remove(current.source);
                     completed.put(current.source, current.result);
                     if (pendingSources.isEmpty()) {
                        rootResult = current.result;
                     } else {
                        final DependencyFrame parent = Objects.requireNonNull(pendingSources.peek());
                        parent.result = parent.result.merge(current.result);
                     }
                     continue;
                  }

                  final AbstractInsnNode dependency = current.dependencies.next();
                  final DependencySummary cached = completed.get(dependency);
                  if (cached != null) {
                     current.result = current.result.merge(cached);
                  } else if (active.add(dependency)) {
                     final DependencyExpansion expansion = expansionResolver.determineExpansion(dependency);
                     if (expansion.dependencies == null) {
                        active.remove(dependency);
                        completed.put(dependency, expansion.summary);
                        current.result = current.result.merge(expansion.summary);
                     } else {
                        pendingSources.push(new DependencyFrame(dependency, Objects.requireNonNull(expansion.dependencies)));
                     }
                  } else
                     return DependencySummary.UNKNOWN;
                  if (!current.result.proven)
                     return DependencySummary.UNKNOWN;
               }
            }
         }
         result = result.merge(Objects.requireNonNull(rootResult));
         if (!result.proven)
            return result;
      }
      return result;
   }

   private static boolean isDefinitelyNonNullValue(final SourceValue value, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes) {
      return areAllSourcesProven(value, source -> determineNonNullSourceDependencies(source, frames, instructionIndexes));
   }

   private static boolean isExactAllocationValue(final SourceValue value, final String owner, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes) {
      return areAllSourcesProven(value, source -> {
         if (source.getOpcode() == Opcodes.NEW)
            // Equality is deliberately strict: resolving an inherited target for NEW Sub / INVOKEVIRTUAL Base is outside this proof.
            return owner.equals(((TypeInsnNode) source).desc) ? Set.of() : null;
         return determineForwardedSourceDependencies(source, frames, instructionIndexes);
      });
   }

   private static void mergeFieldState(final boolean[] states, final boolean[] reached, final Deque<Integer> pendingInstructions,
         final int instructionIndex, final boolean incomingState) {
      if (!reached[instructionIndex]) {
         reached[instructionIndex] = true;
         states[instructionIndex] = incomingState;
         pendingInstructions.add(instructionIndex);
      } else if (states[instructionIndex] && !incomingState) {
         // The merge is an AND: a field is proven non-null only when every path reaching this instruction proves it.
         states[instructionIndex] = false;
         pendingInstructions.add(instructionIndex);
      }
   }

   @SuppressWarnings("null")
   private static boolean isDefinitelyInitializedNonNull(final String owner, final String field, final AbstractInsnNode[] instructions,
         final Frame<SourceValue>[] frames, final Map<AbstractInsnNode, Integer> instructionIndexes, final ControlFlowAnalyzer analyzer) {
      if (instructions.length == 0)
         return false;

      /* EEA field contracts describe normally initialized classes. Code reached recursively from <clinit> can observe
       * JVM default values before assignment, but treating that exceptional window as the field's ordinary contract
       * would make source-level non-null static-final fields impossible to infer. */
      final boolean[] states = new boolean[instructions.length];
      final boolean[] reached = new boolean[instructions.length];
      final Deque<Integer> pendingInstructions = new ArrayDeque<>();
      reached[0] = true;
      states[0] = false;
      pendingInstructions.add(0);
      boolean hasNormalReturn = false;

      while (!pendingInstructions.isEmpty()) {
         final int instructionIndex = pendingInstructions.removeFirst();
         final boolean incomingState = states[instructionIndex];
         boolean outgoingState = incomingState;
         final AbstractInsnNode instruction = instructions[instructionIndex];

         if (instruction.getOpcode() == Opcodes.PUTSTATIC) {
            final FieldInsnNode fieldInsn = (FieldInsnNode) instruction;
            if (fieldInsn.owner.equals(owner) && fieldKey(fieldInsn.name, fieldInsn.desc).equals(field)) {
               final Frame<SourceValue> frame = frames[instructionIndex];
               outgoingState = frame.getStackSize() > 0 && isDefinitelyNonNullValue(frame.getStack(frame.getStackSize() - 1), frames,
                  instructionIndexes);
            }
         } else if (instruction.getOpcode() == Opcodes.RETURN) {
            // If <clinit> exits abruptly the class is unusable, so only normal returns need a non-null field value.
            hasNormalReturn = true;
            if (!outgoingState)
               return false;
         }

         for (final int successor : analyzer.normalSuccessors.get(instructionIndex)) {
            mergeFieldState(states, reached, pendingInstructions, successor, outgoingState);
         }
         for (final int successor : analyzer.exceptionSuccessors.get(instructionIndex)) {
            // An instruction may throw before completing a PUTSTATIC, so handlers see the incoming field state.
            mergeFieldState(states, reached, pendingInstructions, successor, incomingState);
         }
      }
      return hasNormalReturn;
   }

   @SuppressWarnings("null")
   private static Set<String> determineDefinitelyNonNullStaticFields(final ClassNode targetClass) {
      final Set<String> candidates = new HashSet<>();
      final Set<String> constantValueFields = new HashSet<>();
      for (final FieldNode field : targetClass.fields) {
         // Only final fields retain the value proven in <clinit>; mutable fields may change before a method reads them.
         if ((field.access & (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)) == (Opcodes.ACC_STATIC | Opcodes.ACC_FINAL) && isReferenceType(
            field.desc)) {
            final String key = fieldKey(field.name, field.desc);
            candidates.add(key);
            if (field.value != null) {
               constantValueFields.add(key);
            }
         }
      }

      final Set<String> knownNonNullFields = new HashSet<>();
      if (candidates.isEmpty())
         return knownNonNullFields;

      MethodNode classInitializer = null;
      for (final MethodNode method : targetClass.methods) {
         if (method.name.equals("<clinit>")) {
            classInitializer = method;
            break;
         }
      }

      if (classInitializer == null) {
         knownNonNullFields.addAll(constantValueFields);
         return knownNonNullFields;
      }

      final AbstractInsnNode[] instructions = classInitializer.instructions.toArray();
      final Map<AbstractInsnNode, Integer> instructionIndexes = new IdentityHashMap<>();
      for (int i = 0; i < instructions.length; i++) {
         instructionIndexes.put(instructions[i], i);
      }
      // ConstantValue fields are initialized by the JVM before <clinit>. Keep them only when custom bytecode does not overwrite them.
      for (final String field : constantValueFields) {
         boolean isOverwritten = false;
         for (final AbstractInsnNode instruction : instructions) {
            if (instruction.getOpcode() == Opcodes.PUTSTATIC) {
               final FieldInsnNode fieldInsn = (FieldInsnNode) instruction;
               if (fieldInsn.owner.equals(targetClass.name) && fieldKey(fieldInsn.name, fieldInsn.desc).equals(field)) {
                  isOverwritten = true;
                  break;
               }
            }
         }
         if (!isOverwritten) {
            knownNonNullFields.add(field);
         }
      }
      if (knownNonNullFields.containsAll(candidates))
         return knownNonNullFields;

      if (!isWithinAnalysisBudget(classInitializer)) {
         /* The earlier constant/provenance checks remain valid. Only the optional whole-method dataflow is skipped. */
         logAnalysisBudgetExceeded(targetClass.name, classInitializer);
         return knownNonNullFields;
      }

      try {
         final var analyzer = new ControlFlowAnalyzer(instructions.length);
         final Frame<SourceValue>[] frames = analyzer.analyze(targetClass.name, classInitializer);
         for (final String field : candidates) {
            if (!knownNonNullFields.contains(field) && isDefinitelyInitializedNonNull(targetClass.name, field, instructions, frames,
               instructionIndexes, analyzer)) {
               knownNonNullFields.add(field);
            }
         }
      } catch (final AnalyzerException ex) {
         // Field analysis only adds positive evidence; unsupported bytecode must leave field reads unknown.
         System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
            "Failed to analyze static field initialization of " + targetClass.name, ex);
      }
      return knownNonNullFields;
   }

   private static void mergeGuaranteedNullParameters(final List<Set<Integer>> states, final boolean[] reached,
         final Deque<Integer> pendingInstructions, final int instructionIndex, final Set<Integer> incomingState) {
      if (!reached[instructionIndex]) {
         reached[instructionIndex] = true;
         states.set(instructionIndex, new HashSet<>(incomingState));
         pendingInstructions.add(instructionIndex);
         return;
      }

      final Set<Integer> currentState = states.get(instructionIndex);
      final Set<Integer> mergedState = new HashSet<>();
      if (!currentState.isEmpty() && !incomingState.isEmpty()) {
         /* Each predecessor proves that at least one parameter in its set is null. After joining alternative paths,
          * the union is the weakest fact true on both paths. One predecessor without such proof clears the fact. */
         mergedState.addAll(currentState);
         mergedState.addAll(incomingState);
      }
      if (!mergedState.equals(currentState)) {
         states.set(instructionIndex, mergedState);
         pendingInstructions.add(instructionIndex);
      }
   }

   private static void mergeGuaranteedNonNullParameters(final List<Set<Integer>> states, final boolean[] reached,
         final Deque<Integer> pendingInstructions, final int instructionIndex, final Set<Integer> incomingState) {
      if (!reached[instructionIndex]) {
         reached[instructionIndex] = true;
         states.set(instructionIndex, new HashSet<>(incomingState));
         pendingInstructions.add(instructionIndex);
         return;
      }

      final Set<Integer> currentState = states.get(instructionIndex);
      final Set<Integer> mergedState = new HashSet<>(currentState);
      // Non-nullness is a must-fact: a join retains only parameters proven non-null by every predecessor.
      mergedState.retainAll(incomingState);
      if (!mergedState.equals(currentState)) {
         states.set(instructionIndex, mergedState);
         pendingInstructions.add(instructionIndex);
      }
   }

   private static String methodKey(final String name, final String descriptor) {
      return name + '\0' + descriptor;
   }

   private static @Nullable MethodNode findMethodNode(final ClassNode owner, final String name, final String descriptor) {
      for (final MethodNode method : owner.methods) {
         if (method.name.equals(name) && method.desc.equals(descriptor))
            return method;
      }
      return null;
   }

   private @Nullable MethodNode findMethodNode(final String name, final String descriptor) {
      return findMethodNode(classNode, name, descriptor);
   }

   // ASM's tree and frame APIs are not null-annotated and use null frames for unreachable instructions.
   @SuppressWarnings("null")
   private static Map<Integer, Integer> determineReferenceParameterIndexesByLocalSlot(final MethodNode method) {
      return determineReferenceParameterIndexesByLocalSlot(method.access, method.desc);
   }

   private static Map<Integer, Integer> determineReferenceParameterIndexesByLocalSlot(final int access, final String descriptor) {
      final Map<Integer, Integer> result = new HashMap<>();
      int localSlot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
      @SuppressWarnings("null")
      final Type[] argumentTypes = Type.getArgumentTypes(descriptor);
      for (int parameterIndex = 0; parameterIndex < argumentTypes.length; parameterIndex++) {
         final Type argumentType = argumentTypes[parameterIndex];
         if (argumentType.getSort() == Type.OBJECT || argumentType.getSort() == Type.ARRAY) {
            result.put(localSlot, parameterIndex);
         }
         // long and double consume two JVM local slots, so parameter count cannot be used as a slot boundary.
         localSlot += argumentType.getSize();
      }
      return result;
   }

   @SuppressWarnings("null")
   private static DependencySummary determineDirectParameterDependencies(final SourceValue value, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot) {
      return determineDependencies(value, source -> {
         final Integer sourceIndex = instructionIndexes.get(source);
         if (sourceIndex == null)
            return DependencyExpansion.terminal(DependencySummary.UNKNOWN);

         switch (source.getOpcode()) {
            case Opcodes.ALOAD: {
               final int localSlot = ((VarInsnNode) source).var;
               final Integer parameterIndex = referenceParameterIndexesByLocalSlot.get(localSlot);
               final Frame<SourceValue> frame = frames[sourceIndex];
               if (frame == null || localSlot >= frame.getLocals())
                  return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
               final SourceValue localValue = frame.getLocal(localSlot);
               /* An untouched parameter has no producer instruction in ASM's entry frame. If its slot was reassigned,
                * follow the replacement value instead of attributing that value to the original parameter. */
               return parameterIndex != null && localValue.insns.isEmpty() //
                     ? DependencyExpansion.terminal(DependencySummary.dependentOn(Set.of(parameterIndex))) //
                     : DependencyExpansion.forwarded(localValue);
            }
            case Opcodes.ASTORE:
            case Opcodes.CHECKCAST:
            case Opcodes.DUP: {
               final Frame<SourceValue> frame = frames[sourceIndex];
               return frame != null && frame.getStackSize() > 0 //
                     ? DependencyExpansion.forwarded(frame.getStack(frame.getStackSize() - 1)) //
                     : DependencyExpansion.terminal(DependencySummary.UNKNOWN);
            }
            default:
               // A null check establishes a parameter fact only through aliases and casts, not arbitrary computation.
               return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
         }
      });
   }

   private static Set<Integer> addDependencies(final Set<Integer> state, final Set<Integer> additionalDependencies) {
      final Set<Integer> result = new HashSet<>(state);
      result.addAll(additionalDependencies);
      return result;
   }

   @SuppressWarnings("null")
   private static SourceValue[] determineArgumentValues(final MethodInsnNode call, final @Nullable Frame<SourceValue> frame) {
      final Type[] argumentTypes = Type.getArgumentTypes(call.desc);
      if (frame == null || frame.getStackSize() < argumentTypes.length)
         return new SourceValue[0];

      /* ASM frames store one SourceValue per operand, including category-2 values. Build the argument array backwards
       * because the last declared argument is on top of the operand stack. The receiver, when present, remains below it. */
      final SourceValue[] argumentValues = new SourceValue[argumentTypes.length];
      int stackIndex = frame.getStackSize() - 1;
      for (int argumentIndex = argumentValues.length - 1; argumentIndex >= 0; argumentIndex--) {
         argumentValues[argumentIndex] = frame.getStack(stackIndex--);
      }
      return argumentValues;
   }

   private static @Nullable SourceValue determineReceiverValue(final MethodInsnNode call, final @Nullable Frame<SourceValue> frame) {
      if (frame == null || call.getOpcode() == Opcodes.INVOKESTATIC)
         return null;

      // ASM frames use one stack entry per value, so the receiver is immediately below the declared arguments.
      final int receiverStackIndex = frame.getStackSize() - Type.getArgumentTypes(call.desc).length - 1;
      return receiverStackIndex >= 0 ? frame.getStack(receiverStackIndex) : null;
   }

   private static boolean catchesNullPointerException(final @Nullable String caughtType) {
      /* NPE, RuntimeException, Exception, and Throwable are the complete declared superclass chain that can catch the
       * exact NullPointerException thrown for a null receiver. A catch of another RuntimeException subtype cannot. */
      return "java/lang/NullPointerException".equals(caughtType) || "java/lang/RuntimeException".equals(caughtType) || "java/lang/Exception"
         .equals(caughtType) || "java/lang/Throwable".equals(caughtType);
   }

   private static Set<AbstractInsnNode> determineInstructionsProtectedByNullPointerExceptionHandler(final MethodNode method) {
      final Set<AbstractInsnNode> protectedInstructions = Collections.newSetFromMap(new IdentityHashMap<>());
      for (final TryCatchBlockNode tryCatchBlock : method.tryCatchBlocks) {
         if (!catchesNullPointerException(tryCatchBlock.type)) {
            continue;
         }

         for (AbstractInsnNode instruction = tryCatchBlock.start; instruction != null
               && instruction != tryCatchBlock.end; instruction = instruction.getNext()) {
            protectedInstructions.add(instruction);
         }
      }
      /* A null catch type represents a catch-all entry, commonly the synthetic handler for finally. Facts already do
       * not cross exception edges, so a swallowing handler clears the proof while a rethrowing finally does not make
       * an otherwise qualifying call look caught by an explicit NPE-capable catch clause. */
      return protectedInstructions;
   }

   private static boolean isNullRejectingReceiverCall(final MethodInsnNode call) {
      final int opcode = call.getOpcode();
      return opcode == Opcodes.INVOKEVIRTUAL || opcode == Opcodes.INVOKEINTERFACE || opcode == Opcodes.INVOKESPECIAL && !call.name.equals(
         "<init>");
   }

   @SuppressWarnings("null")
   private static List<Set<Integer>> determineGuaranteedNullParameters(final AbstractInsnNode[] instructions,
         final Frame<SourceValue>[] frames, final ControlFlowAnalyzer controlFlow, final Map<AbstractInsnNode, Integer> instructionIndexes,
         final Map<Integer, Integer> referenceParameterIndexesByLocalSlot) {
      final List<Set<Integer>> states = new ArrayList<>(instructions.length);
      for (int i = 0; i < instructions.length; i++) {
         states.add(Set.of());
      }
      if (instructions.length == 0)
         return states;

      final boolean[] reached = new boolean[instructions.length];
      final Deque<Integer> pendingInstructions = new ArrayDeque<>();
      reached[0] = true;
      pendingInstructions.add(0);

      while (!pendingInstructions.isEmpty()) {
         final int instructionIndex = pendingInstructions.removeFirst();
         final Set<Integer> incomingState = states.get(instructionIndex);
         final AbstractInsnNode instruction = instructions[instructionIndex];
         final int opcode = instruction.getOpcode();

         DependencySummary testedValueDependencies = DependencySummary.UNKNOWN;
         Integer jumpTargetIndex = null;
         if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && frames[instructionIndex] != null && frames[instructionIndex]
            .getStackSize() > 0) {
            testedValueDependencies = determineDirectParameterDependencies(frames[instructionIndex].getStack(frames[instructionIndex]
               .getStackSize() - 1), frames, instructionIndexes, referenceParameterIndexesByLocalSlot);
            jumpTargetIndex = instructionIndexes.get(((JumpInsnNode) instruction).label);
         }

         for (final int successor : controlFlow.normalSuccessors.get(instructionIndex)) {
            Set<Integer> outgoingState = incomingState;
            if (jumpTargetIndex != null && testedValueDependencies.proven && !testedValueDependencies.parameterIndexes.isEmpty()) {
               final boolean isNullEdge = successor == jumpTargetIndex ? opcode == Opcodes.IFNULL : opcode == Opcodes.IFNONNULL;
               if (isNullEdge) {
                  outgoingState = addDependencies(incomingState, testedValueDependencies.parameterIndexes);
               }
            }
            mergeGuaranteedNullParameters(states, reached, pendingInstructions, successor, outgoingState);
         }
         for (final int successor : controlFlow.exceptionSuccessors.get(instructionIndex)) {
            // A handler observes facts established before the throwing instruction, not effects requiring normal completion.
            mergeGuaranteedNullParameters(states, reached, pendingInstructions, successor, incomingState);
         }
      }
      return states;
   }

   private static List<Set<Integer>> determineGuaranteedNonNullParameters(final MethodNode method, final AbstractInsnNode[] instructions,
         final Frame<SourceValue>[] frames, final ControlFlowAnalyzer controlFlow, final Map<AbstractInsnNode, Integer> instructionIndexes,
         final Map<Integer, Integer> referenceParameterIndexesByLocalSlot, final NonNullParameterEvidenceScope evidenceScope) {
      final List<Set<Integer>> states = new ArrayList<>(instructions.length);
      for (int i = 0; i < instructions.length; i++) {
         states.add(Set.of());
      }
      if (instructions.length == 0)
         return states;

      final boolean[] reached = new boolean[instructions.length];
      final Deque<Integer> pendingInstructions = new ArrayDeque<>();
      final Set<AbstractInsnNode> instructionsProtectedByNullPointerExceptionHandler = evidenceScope == NonNullParameterEvidenceScope.RECEIVER_CALL_CONTRACT
            ? determineInstructionsProtectedByNullPointerExceptionHandler(method)
            : Set.of();
      reached[0] = true;
      pendingInstructions.add(0);

      while (!pendingInstructions.isEmpty()) {
         final int instructionIndex = pendingInstructions.removeFirst();
         final Set<Integer> incomingState = states.get(instructionIndex);
         final AbstractInsnNode instruction = instructions[instructionIndex];

         Set<Integer> normalCompletionState = incomingState;
         if (instruction instanceof MethodInsnNode) {
            final MethodInsnNode call = (MethodInsnNode) instruction;
            final Set<Integer> provenParameters = new HashSet<>();
            if (evidenceScope == NonNullParameterEvidenceScope.RECEIVER_CALL_CONTRACT && isNullRejectingReceiverCall(call)
                  && !instructionsProtectedByNullPointerExceptionHandler.contains(instruction)) {
               final SourceValue receiver = determineReceiverValue(call, frames[instructionIndex]);
               if (receiver != null) {
                  final DependencySummary receiverDependencies = determineDirectParameterDependencies(receiver, frames, instructionIndexes,
                     referenceParameterIndexesByLocalSlot);
                  /* A successful instance invocation proves its receiver non-null. Require one original parameter so
                   * a merged receiver cannot incorrectly establish the fact for every possible producer. */
                  if (receiverDependencies.proven && receiverDependencies.parameterIndexes.size() == 1) {
                     provenParameters.add(receiverDependencies.parameterIndexes.iterator().next());
                  }
               }
            } else if (evidenceScope == NonNullParameterEvidenceScope.RETURN_DEPENDENCY && call.getOpcode() == Opcodes.INVOKESTATIC
                  && call.owner.equals("java/lang/System") && call.name.equals("arraycopy") && call.desc.equals(
                     "(Ljava/lang/Object;ILjava/lang/Object;II)V")) {
               final SourceValue[] arguments = determineArgumentValues(call, frames[instructionIndex]);
               if (arguments.length == 5) {
                  for (final int argumentIndex : new int[] {0, 2}) {
                     final DependencySummary argumentDependencies = determineDirectParameterDependencies(arguments[argumentIndex], frames,
                        instructionIndexes, referenceParameterIndexesByLocalSlot);
                     /* A normal arraycopy return proves its source and destination non-null because a null value would have thrown.
                      * Only a single resolved parameter is safe: an aliased value with multiple producers does not prove each producer. */
                     if (argumentDependencies.proven && argumentDependencies.parameterIndexes.size() == 1) {
                        provenParameters.add(argumentDependencies.parameterIndexes.iterator().next());
                     }
                  }
               }
            }
            normalCompletionState = addDependencies(incomingState, provenParameters);
         }

         for (final int successor : controlFlow.normalSuccessors.get(instructionIndex)) {
            mergeGuaranteedNonNullParameters(states, reached, pendingInstructions, successor, normalCompletionState);
         }
         for (final int successor : controlFlow.exceptionSuccessors.get(instructionIndex)) {
            /* A receiver call or System.arraycopy may throw before the relevant null check has completed. Facts never
             * cross an exception edge; this also prevents a caught NPE from becoming parameter-contract evidence. */
            mergeGuaranteedNonNullParameters(states, reached, pendingInstructions, successor, incomingState);
         }
      }
      return states;
   }

   @SuppressWarnings("null")
   private MethodAnalysis analyzeMethod(final MethodNode method, final NonNullParameterEvidenceScope nonNullParameterEvidenceScope)
         throws AnalyzerException {
      final AbstractInsnNode[] instructions = method.instructions.toArray();
      final var controlFlow = new ControlFlowAnalyzer(instructions.length);
      final Frame<SourceValue>[] frames = controlFlow.analyze(classNode.name, method);
      final Map<AbstractInsnNode, Integer> instructionIndexes = new IdentityHashMap<>();
      for (int i = 0; i < instructions.length; i++) {
         instructionIndexes.put(instructions[i], i);
      }
      final Map<Integer, Integer> parameterIndexes = determineReferenceParameterIndexesByLocalSlot(method);
      final List<Set<Integer>> guaranteedNullParameters = determineGuaranteedNullParameters(instructions, frames, controlFlow,
         instructionIndexes, parameterIndexes);
      /* System.arraycopy facts refine return dependencies but are outside the receiver-call contract requested for
       * generated parameter annotations. Keep these evidence domains separate so neither feature silently expands. */
      final List<Set<Integer>> guaranteedNonNullParameters = determineGuaranteedNonNullParameters(method, instructions, frames, controlFlow,
         instructionIndexes, parameterIndexes, nonNullParameterEvidenceScope);
      return new MethodAnalysis(instructions, frames, instructionIndexes, parameterIndexes, guaranteedNullParameters,
         guaranteedNonNullParameters, (method.access & Opcodes.ACC_STATIC) == 0);
   }

   private DependencySummary determineMethodDependencySummary(final MethodNode method) {
      @SuppressWarnings("null")
      final String key = methodKey(method.name, method.desc);
      final DependencySummary cached = methodDependencySummaries.get(key);
      if (cached != null)
         return cached;
      if (!isWithinAnalysisBudget(method)) {
         logAnalysisBudgetExceeded(classNode.name, method);
         methodDependencySummaries.put(key, DependencySummary.UNKNOWN);
         return DependencySummary.UNKNOWN;
      }
      if (!methodsBeingSummarized.add(key))
         // Recursive summaries need a fixed-point calculation; falling back avoids optimistic results from partial evidence.
         return DependencySummary.UNKNOWN;

      DependencySummary result = DependencySummary.UNKNOWN;
      try {
         methodSummaryResolver.enterMethodSummary();
         try {
            if ((method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0) {
               result = determineMethodDependencySummary(analyzeMethod(method, NonNullParameterEvidenceScope.RETURN_DEPENDENCY));
            }
         } catch (final AnalyzerException ex) {
            // Dependency analysis only adds positive evidence; unsupported bytecode must leave the result unknown.
            System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING, "Failed to analyze null dependencies of "
                  + classNode.name + "." + method.name + method.desc, ex);
         } finally {
            methodSummaryResolver.exitMethodSummary();
         }
      } finally {
         methodsBeingSummarized.remove(key);
      }
      // A depth-limit exception bypasses this cache so later shallow analyses are independent of traversal order.
      methodDependencySummaries.put(key, result);
      return result;
   }

   @SuppressWarnings("null")
   private DependencySummary determineMethodDependencySummary(final MethodAnalysis analysis) {
      DependencySummary result = DependencySummary.NON_NULL;
      boolean hasReferenceReturn = false;
      for (int i = 0; i < analysis.instructions.length; i++) {
         final Frame<SourceValue> frame = analysis.frames[i];
         if (frame == null || analysis.instructions[i].getOpcode() != Opcodes.ARETURN) {
            continue;
         }
         if (frame.getStackSize() == 0) {
            continue;
         }
         hasReferenceReturn = true;
         final DependencySummary returnedValue = determineValueDependencies(frame.getStack(frame.getStackSize() - 1), analysis);
         result = result.merge(returnedValue);
         if (!result.proven)
            return result;
      }
      return hasReferenceReturn ? result : DependencySummary.UNKNOWN;
   }

   private DependencySummary determineValueDependencies(final SourceValue value, final MethodAnalysis analysis) {
      return determineDependencies(value, source -> determineSourceExpansion(source, analysis));
   }

   @SuppressWarnings("null")
   private DependencyExpansion determineSourceExpansion(final AbstractInsnNode source, final MethodAnalysis analysis) {
      final Integer sourceIndex = analysis.instructionIndexes.get(source);
      if (sourceIndex == null)
         return DependencyExpansion.terminal(DependencySummary.UNKNOWN);

      switch (source.getOpcode()) {
         case Opcodes.ACONST_NULL:
            return DependencyExpansion.terminal(DependencySummary.dependentOn(analysis.guaranteedNullParameters.get(sourceIndex)));
         case Opcodes.NEW:
         case Opcodes.NEWARRAY:
         case Opcodes.ANEWARRAY:
         case Opcodes.MULTIANEWARRAY:
            return DependencyExpansion.terminal(DependencySummary.NON_NULL);
         case Opcodes.LDC:
            return DependencyExpansion.terminal(((LdcInsnNode) source).cst instanceof ConstantDynamic //
                  ? DependencySummary.UNKNOWN //
                  : DependencySummary.NON_NULL);
         case Opcodes.GETSTATIC: {
            final FieldInsnNode field = (FieldInsnNode) source;
            return DependencyExpansion.terminal(isDefinitelyNonNullStaticField(field.owner, field.name, field.desc) //
                  ? DependencySummary.NON_NULL //
                  : DependencySummary.UNKNOWN);
         }
         case Opcodes.ALOAD: {
            final int localSlot = ((VarInsnNode) source).var;
            final Integer parameterIndex = analysis.referenceParameterIndexesByLocalSlot.get(localSlot);
            final Frame<SourceValue> frame = analysis.frames[sourceIndex];
            if (frame == null || localSlot >= frame.getLocals())
               return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
            final SourceValue localValue = frame.getLocal(localSlot);
            if (analysis.hasReceiver && localSlot == 0 && localValue.insns.isEmpty())
               /* The JVM checks the receiver before entering an instance method. Trust only the source-less initial
                * slot because valid bytecode may overwrite local slot 0 later. */
               return DependencyExpansion.terminal(DependencySummary.NON_NULL);
            if (parameterIndex != null && localValue.insns.isEmpty())
               // A raw parameter load is normally dependent on that parameter, unless every path to this load has validated it.
               return DependencyExpansion.terminal(analysis.guaranteedNonNullParameters.get(sourceIndex).contains(parameterIndex) //
                     ? DependencySummary.NON_NULL //
                     : DependencySummary.dependentOn(Set.of(parameterIndex)));
            // Parameter slots are ordinary local slots and may be reassigned; analyze the current value after such a write.
            return DependencyExpansion.forwarded(localValue);
         }
         case Opcodes.ASTORE:
         case Opcodes.CHECKCAST:
         case Opcodes.DUP: {
            final Frame<SourceValue> frame = analysis.frames[sourceIndex];
            return frame != null && frame.getStackSize() > 0 //
                  ? DependencyExpansion.forwarded(frame.getStack(frame.getStackSize() - 1)) //
                  : DependencyExpansion.terminal(DependencySummary.UNKNOWN);
         }
         case Opcodes.INVOKEVIRTUAL:
         case Opcodes.INVOKESPECIAL:
         case Opcodes.INVOKESTATIC:
         case Opcodes.INVOKEINTERFACE:
            return determineMethodCallExpansion((MethodInsnNode) source, sourceIndex, analysis);
         default:
            return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
      }
   }

   @SuppressWarnings("null")
   private DependencyExpansion determineMethodCallExpansion(final MethodInsnNode call, final int instructionIndex,
         final MethodAnalysis callerAnalysis) {
      // These are deliberate call-contract heuristics; the dispatch restriction below applies only to body-derived summaries.
      if (isKnownNonNullMethod(call.getOpcode(), call.owner, call.name, call.desc))
         return DependencyExpansion.terminal(DependencySummary.NON_NULL);
      final int opcode = call.getOpcode();
      final Frame<SourceValue> frame = callerAnalysis.frames[instructionIndex];
      final SourceValue receiver = determineReceiverValue(call, frame);
      final boolean receiverHasExactType = receiver != null && opcode == Opcodes.INVOKEVIRTUAL && isExactAllocationValue(receiver,
         call.owner, callerAnalysis.frames, callerAnalysis.instructionIndexes);
      if (!call.owner.equals(classNode.name))
         return DependencyExpansion.terminal(methodSummaryResolver.determineExternalMethodSummary(call, receiverHasExactType));

      final MethodNode calledMethod = findMethodNode(call.name, call.desc);
      if (calledMethod == null)
         return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
      final boolean ownerIsFinal = (classNode.access & Opcodes.ACC_FINAL) != 0;
      final boolean methodIsFinal = (calledMethod.access & Opcodes.ACC_FINAL) != 0;
      final boolean isOverridableVirtualCall = opcode == Opcodes.INVOKEVIRTUAL && !ownerIsFinal && !methodIsFinal;
      if (opcode == Opcodes.INVOKEINTERFACE || isOverridableVirtualCall && !receiverHasExactType)
         /* A classpath scan cannot prove that consumers will not add another subclass. The declared body is safe only
          * for statically bound calls, final dispatch, or an exact allocation at this call site; an override may
          * otherwise have a different null contract. */
         return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
      final DependencySummary calledMethodSummary = determineMethodDependencySummary(calledMethod);
      if (!calledMethodSummary.proven || calledMethodSummary.parameterIndexes.isEmpty())
         return DependencyExpansion.terminal(calledMethodSummary);

      final SourceValue[] argumentValues = determineArgumentValues(call, frame);
      final Set<AbstractInsnNode> dependencies = Collections.newSetFromMap(new IdentityHashMap<>());
      for (final int parameterIndex : calledMethodSummary.parameterIndexes) {
         if (parameterIndex < 0 || parameterIndex >= argumentValues.length)
            return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
         if (argumentValues[parameterIndex].insns.isEmpty())
            return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
         dependencies.addAll(argumentValues[parameterIndex].insns);
      }
      return DependencyExpansion.forwarded(dependencies);
   }

   /* ASM stores null frames for unreachable instructions despite exposing an unannotated array. ECJ therefore needs
    * both suppressions for the defensive null-frame branch below. */
   @SuppressWarnings({"null", "unused"})
   private ReturnEvidence determineReturnEvidence(final MethodNode methodNode) {

      final AbstractInsnNode[] instructions = methodNode.instructions.toArray();
      /* Unknown parameters, fields, and calls can invalidate a path-insensitive non-null result without introducing
       * ACONST_NULL. Inspect every reachable reference return rather than using null constants as an entry condition. */
      try {
         Frame<SourceValue>[] frames = new FlowAnalyzer(new FlowInterpreter(), Map.of()).analyze(classNode.name, methodNode);
         final Map<AbstractInsnNode, String> testedPrivateFields = new IdentityHashMap<>();
         for (int i = 0; i < instructions.length; i++) {
            final int opcode = instructions[i].getOpcode();
            final Frame<SourceValue> frame = frames[i];
            if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && frame != null && frame.getStackSize() > 0) {
               final SourceValue testedValue = frame.getStack(frame.getStackSize() - 1);
               if (testedValue instanceof FlowValue) {
                  final String privateFieldKey = ((FlowValue) testedValue).privateThisFieldKey;
                  if (privateFieldKey != null) {
                     testedPrivateFields.put(instructions[i], privateFieldKey);
                  }
               }
            }
         }
         // Both must-fact passes and sentinel validation consume one allowance. Otherwise each phase could stay below
         // the nominal cap while their cumulative work remains effectively unbounded.
         final var fieldFactBudget = new FieldFactBudget();
         if (!testedPrivateFields.isEmpty()) {
            /* Compute field facts separately from ASM's widening value lattice. Calls and writes kill these must-facts,
             * so a local holding an earlier field value cannot make a later field read look non-null. */
            final Map<AbstractInsnNode, Set<String>> knownFieldsAtInstruction = determineKnownNonNullFieldsAtInstructions(methodNode,
               instructions, Map.of(), testedPrivateFields, fieldFactBudget);
            frames = new FlowAnalyzer(new FlowInterpreter(), knownFieldsAtInstruction).analyze(classNode.name, methodNode);
         }

         final Map<AbstractInsnNode, Set<String>> candidateBranchRequirements = new IdentityHashMap<>();
         final Map<AbstractInsnNode, FlowValue> testedBranchValues = new IdentityHashMap<>();
         for (int i = 0; i < instructions.length; i++) {
            final int opcode = instructions[i].getOpcode();
            final Frame<SourceValue> frame = frames[i];
            if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && frame != null && frame.getStackSize() > 0) {
               final SourceValue testedValue = frame.getStack(frame.getStackSize() - 1);
               if (testedValue instanceof FlowValue) {
                  final FlowValue testedFlowValue = (FlowValue) testedValue;
                  if (testedFlowValue.nullConstantPath && !testedFlowValue.mayHaveNonConstantNullPath
                        && !testedFlowValue.requiredNonNullFieldsForNullConstantPath.isEmpty()) {
                     candidateBranchRequirements.put(instructions[i], testedFlowValue.requiredNonNullFieldsForNullConstantPath);
                     testedBranchValues.put(instructions[i], testedFlowValue);
                  }
               }
            }
         }
         final Map<AbstractInsnNode, Set<String>> branchRequirements = retainPreservedNullBranchRequirements(methodNode, instructions,
            candidateBranchRequirements, testedBranchValues, fieldFactBudget);
         if (!branchRequirements.isEmpty()) {
            /* A null sentinel can encode a fact from the path on which it was created. Settle that relation in the CFG
             * before the final value pass; otherwise an earlier broad frame cannot be narrowed later. */
            final Map<AbstractInsnNode, Set<String>> knownFieldsAtInstruction = determineKnownNonNullFieldsAtInstructions(methodNode,
               instructions, branchRequirements, testedPrivateFields, fieldFactBudget);
            frames = new FlowAnalyzer(new FlowInterpreter(), knownFieldsAtInstruction).analyze(classNode.name, methodNode);
         }
         boolean hasReachableReturn = false;
         boolean allReturnsAreNonNull = true;
         for (int i = 0; i < instructions.length; i++) {
            final Frame<SourceValue> frame = frames[i];
            if (frame == null) {
               // ASM represents an unreachable instruction with a null array entry despite the generic array annotation.
               continue;
            }
            if (instructions[i].getOpcode() != Opcodes.ARETURN) {
               continue;
            }
            if (!(frame instanceof FlowFrame))
               return ReturnEvidence.UNKNOWN;
            if (!((FlowFrame) frame).reachable) {
               continue;
            }
            hasReachableReturn = true;
            if (frame.getStackSize() == 0)
               return ReturnEvidence.UNKNOWN;

            final SourceValue returnedValue = frame.getStack(frame.getStackSize() - 1);
            if (!(returnedValue instanceof FlowValue))
               return ReturnEvidence.UNKNOWN;
            final FlowNullness returnNullness = ((FlowValue) returnedValue).nullness;
            if (returnNullness == FlowNullness.DEFINITELY_NULL || returnNullness == FlowNullness.MAY_INCLUDE_NULL)
               /* MAY_INCLUDE_NULL is not ordinary uncertainty: its merge invariant requires at least one proven-null
                * producer. This also recognizes a parameter returned from an edge on which IFNULL proved it null. */
               return ReturnEvidence.PROVEN_NULL;
            if (returnNullness != FlowNullness.NEVER_NULL) {
               allReturnsAreNonNull = false;
            }
         }
         return hasReachableReturn && allReturnsAreNonNull ? ReturnEvidence.PROVEN_NON_NULL : ReturnEvidence.UNKNOWN;
      } catch (final AnalyzerException ex) {
         // Flow analysis contributes only positive evidence; unsupported bytecode must remain unknown.
         System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING, "Failed to analyze control flow of "
               + classNode.name + "." + methodNode.name + methodNode.desc, ex);
         return ReturnEvidence.UNKNOWN;
      }
   }

   /**
    * Returns whether bytecode proves that the field has a non-null value after every normal class-initializer completion.
    */
   @SuppressWarnings("null")
   public boolean isDefinitelyNonNullStaticField(final FieldInfo fieldInfo) {
      final String owner = fieldInfo.getClassInfo().getName().replace('.', '/');
      return isDefinitelyNonNullStaticField(owner, fieldInfo.getName(), fieldInfo.getTypeDescriptorStr());
   }

   private static boolean isNormalReturnInstruction(final int opcode) {
      return opcode == Opcodes.IRETURN || opcode == Opcodes.LRETURN || opcode == Opcodes.FRETURN || opcode == Opcodes.DRETURN
            || opcode == Opcodes.ARETURN || opcode == Opcodes.RETURN;
   }

   /**
    * Returns the zero-based descriptor indexes of reference parameters that bytecode proves non-null through direct receiver calls on every
    * normal return path.
    */
   @SuppressWarnings({"null", "unused"})
   public Set<Integer> determineDefinitelyNonNullMethodParameters(final MethodInfo methodInfo) {
      final String methodName = methodInfo.getName();
      final @NonNull String methodDescriptor = methodInfo.getTypeDescriptorStr();
      final MethodNode methodNode = findMethodNode(methodName, methodDescriptor);
      if (methodNode == null || (methodNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0
            || determineReferenceParameterIndexesByLocalSlot(methodNode).isEmpty())
         return Set.of();
      if (!isWithinAnalysisBudget(methodNode)) {
         logAnalysisBudgetExceeded(classNode.name, methodNode);
         return Set.of();
      }

      try {
         final MethodAnalysis analysis = analyzeMethod(methodNode, NonNullParameterEvidenceScope.RECEIVER_CALL_CONTRACT);
         @Nullable
         Set<Integer> result = null;
         for (int i = 0; i < analysis.instructions.length; i++) {
            if (analysis.frames[i] == null || !isNormalReturnInstruction(analysis.instructions[i].getOpcode())) {
               continue;
            }

            if (result == null) {
               result = new HashSet<>(analysis.guaranteedNonNullParameters.get(i));
            } else {
               // The public contract is a must-fact across complete normal executions, not merely one successful branch.
               result.retainAll(analysis.guaranteedNonNullParameters.get(i));
            }
         }

         // An always-throwing method supplies no successful execution from which to infer a caller-facing contract.
         return result == null ? Set.of() : Set.copyOf(result);
      } catch (final AnalyzerException ex) {
         // Parameter inference contributes only positive evidence; unsupported bytecode leaves every parameter unknown.
         System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING, "Failed to analyze parameter nullness of "
               + classNode.name + "." + methodName + methodDescriptor, ex);
      }
      return Set.of();
   }

   /**
    * Analyzes bytecode to determine the nullability of method return types.
    */
   public Nullability determineMethodReturnTypeNullability(final MethodInfo methodInfo) {
      switch (ClassGraphUtils.getMethodReturnKind(methodInfo)) {
         case PRIMITIVE:
         case VOID:
            return Nullability.NEVER_NULL;
         default:
            // continue analysis for object return types
      }

      if (methodInfo.isAbstract())
         return Nullability.UNKNOWN;

      final String methodName = methodInfo.getName();
      @SuppressWarnings("null")
      final @NonNull String methodDescriptor = methodInfo.getTypeDescriptorStr();
      final MethodNode methodNode = findMethodNode(methodName, methodDescriptor);
      if (methodNode == null)
         return Nullability.UNKNOWN;
      if (!isWithinAnalysisBudget(methodNode)) {
         logAnalysisBudgetExceeded(classNode.name, methodNode);
         return Nullability.UNKNOWN;
      }

      final ReturnEvidence returnEvidence = determineReturnEvidence(methodNode);
      if (returnEvidence == ReturnEvidence.PROVEN_NON_NULL)
         return Nullability.NEVER_NULL;

      final DependencySummary dependencySummary;
      try {
         dependencySummary = determineMethodDependencySummary(methodNode);
      } catch (final MethodSummaryDepthExceededException ex) {
         /* The depth budget limits only the stronger dependency proof. A null value already proven to reach ARETURN
          * remains valid nullable evidence even when the analyzer cannot decide whether it depends on a parameter. */
         return returnEvidence == ReturnEvidence.PROVEN_NULL ? Nullability.DEFINITELY_NULL : Nullability.UNKNOWN;
      }

      if (returnEvidence == ReturnEvidence.PROVEN_NULL) {
         if (dependencySummary.proven && !dependencySummary.parameterIndexes.isEmpty())
            /* A dependency alone also describes a plain "return arg" identity method. Require independent flow evidence
             * that a null value reaches ARETURN before interpreting the dependency as PolyNull. */
            return Nullability.POLY_NULL;
         return Nullability.DEFINITELY_NULL;
      }

      if (dependencySummary.proven && dependencySummary.parameterIndexes.isEmpty())
         // Method-call summaries can prove every return non-null even when local flow treats the call result as unknown.
         return Nullability.NEVER_NULL;

      return Nullability.UNKNOWN;
   }
}
