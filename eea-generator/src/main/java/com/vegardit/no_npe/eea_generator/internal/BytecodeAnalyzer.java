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
   /* Depth alone cannot bound a branching helper graph whose cycle-limited summaries cannot be cached. Charge estimated
    * frame and handler work plus local and delegated provenance; every individually eligible root can still begin analysis. */
   private static final long MAX_PARAMETER_ANALYSIS_WORK = 2_000_000;
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

   /**
    * Carries a return classification together with the exact parameters on which a PolyNull result depends.
    * Parameter indexes follow JVM descriptor order.
    */
   public static final class MethodReturnAnalysis {
      private final Set<Integer> nullDependentParameterIndexes;
      private final Nullability nullability;

      private MethodReturnAnalysis(final Nullability nullability, final Set<Integer> nullDependentParameterIndexes) {
         this.nullability = nullability;
         this.nullDependentParameterIndexes = Set.copyOf(nullDependentParameterIndexes);
      }

      public Set<Integer> getNullDependentParameterIndexes() {
         return nullDependentParameterIndexes;
      }

      public Nullability getNullability() {
         return nullability;
      }
   }

   /**
    * Carries parameter contracts proven by bytecode analysis. Parameter indexes follow JVM descriptor order.
    */
   public static final class MethodParameterAnalysis {
      private static final MethodParameterAnalysis EMPTY = new MethodParameterAnalysis(Set.of(), Set.of());

      private final Set<Integer> definitelyNonNullParameterIndexes;
      private final Set<Integer> definitelyNullableParameterIndexes;

      private MethodParameterAnalysis(final Set<Integer> definitelyNullableParameterIndexes,
            final Set<Integer> definitelyNonNullParameterIndexes) {
         this.definitelyNullableParameterIndexes = Set.copyOf(definitelyNullableParameterIndexes);
         this.definitelyNonNullParameterIndexes = Set.copyOf(definitelyNonNullParameterIndexes);
      }

      public Set<Integer> getDefinitelyNonNullParameterIndexes() {
         return definitelyNonNullParameterIndexes;
      }

      public Set<Integer> getDefinitelyNullableParameterIndexes() {
         return definitelyNullableParameterIndexes;
      }
   }

   @FunctionalInterface
   private interface NonReturningCallResolver {
      boolean isNonReturning(MethodInsnNode call);
   }

   @SuppressWarnings("null")
   private static final class ControlFlowAnalyzer extends Analyzer<SourceValue> {
      final List<Set<Integer>> exceptionSuccessors;
      final List<Set<Integer>> normalSuccessors;
      private final AbstractInsnNode @Nullable [] instructions;
      private final @Nullable NonReturningCallResolver nonReturningCallResolver;

      ControlFlowAnalyzer(final int instructionCount) {
         this(instructionCount, null, null);
      }

      ControlFlowAnalyzer(final AbstractInsnNode[] instructions, final NonReturningCallResolver nonReturningCallResolver) {
         this(instructions.length, instructions, nonReturningCallResolver);
      }

      private ControlFlowAnalyzer(final int instructionCount, final AbstractInsnNode @Nullable [] instructions,
            final @Nullable NonReturningCallResolver nonReturningCallResolver) {
         super(new SourceInterpreter());
         this.instructions = instructions;
         this.nonReturningCallResolver = nonReturningCallResolver;
         normalSuccessors = new ArrayList<>(instructionCount);
         exceptionSuccessors = new ArrayList<>(instructionCount);
         for (int i = 0; i < instructionCount; i++) {
            normalSuccessors.add(new HashSet<>());
            exceptionSuccessors.add(new HashSet<>());
         }
      }

      @Override
      protected void newControlFlowEdge(final int instructionIndex, final int successorIndex) {
         final AbstractInsnNode @Nullable [] availableInstructions = instructions;
         final NonReturningCallResolver availableResolver = nonReturningCallResolver;
         if (availableInstructions != null && availableResolver != null && availableInstructions[instructionIndex] instanceof MethodInsnNode
               && availableResolver.isNonReturning((MethodInsnNode) availableInstructions[instructionIndex]))
            // The normal edge is impossible, but Analyzer still reports exception edges separately for matching handlers.
            return;
         normalSuccessors.get(instructionIndex).add(successorIndex);
      }

      @Override
      protected boolean newControlFlowExceptionEdge(final int instructionIndex, final int successorIndex) {
         exceptionSuccessors.get(instructionIndex).add(successorIndex);
         return true;
      }

      @Override
      protected Frame<SourceValue> newFrame(final int numLocals, final int numStack) {
         final NonReturningCallResolver availableResolver = nonReturningCallResolver;
         return availableResolver == null ? super.newFrame(numLocals, numStack)
               : new FlowFrame(numLocals, numStack, Map.of(), availableResolver);
      }

      @Override
      protected Frame<SourceValue> newFrame(final @NonNullByDefault({}) Frame<? extends SourceValue> frame) {
         final NonReturningCallResolver availableResolver = nonReturningCallResolver;
         return availableResolver == null ? super.newFrame(frame) : new FlowFrame(frame, Map.of(), availableResolver);
      }
   }

   /**
    * Shares one work allowance across the phases or descendants of an optional proof.
    */
   private static final class AnalysisWorkBudget {
      private long remainingWork;
      private boolean exceeded;

      AnalysisWorkBudget(final long workAllowance) {
         remainingWork = workAllowance;
      }

      boolean tryConsume(final long workUnits) {
         if (workUnits > remainingWork) {
            // Later phases or sibling calls must not recover a fresh allowance after an expensive branch exhausts it.
            remainingWork = 0;
            exceeded = true;
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

   /** Describes which nullness fact a reference predicate establishes for each Boolean outcome. */
   private enum ReferencePredicate {
      NON_NULL_ON_TRUE,
      IS_NULL,
      NON_NULL;

      FlowNullness edgeNullness(final int opcode, final boolean jumpTaken) {
         // Reference comparisons test equality; IF_ACMPNE, like IFEQ for a Boolean predicate, jumps on the false outcome.
         final boolean result = jumpTaken != (opcode == Opcodes.IFEQ || opcode == Opcodes.IF_ACMPNE);
         if (this == NON_NULL_ON_TRUE)
            // A failed type test or inequality with a non-null reference still admits other non-null objects.
            return result ? FlowNullness.NEVER_NULL : FlowNullness.UNKNOWN;
         return result == (this == IS_NULL) ? FlowNullness.DEFINITELY_NULL : FlowNullness.NEVER_NULL;
      }
   }

   /** Keeps a predicate tied to the tested value, so saved Booleans cannot refine a later local-slot replacement. */
   private static final class ReferenceTest {
      final FlowValue operand;
      final ReferencePredicate predicate;

      ReferenceTest(final FlowValue operand, final ReferencePredicate predicate) {
         this.operand = operand;
         this.predicate = predicate;
      }

      @Override
      public boolean equals(final @Nullable Object obj) {
         return obj instanceof ReferenceTest && operand == ((ReferenceTest) obj).operand && predicate == ((ReferenceTest) obj).predicate;
      }

      @Override
      public int hashCode() {
         return Objects.hash(System.identityHashCode(operand), predicate);
      }
   }

   private static final class FlowValue extends SourceValue {
      final boolean mayHaveNonConstantNullPath;
      final boolean nullConstantPath;
      final FlowNullness nullness;
      final Set<Integer> parameterLocalSlots;
      final @Nullable String privateThisFieldKey;
      final Set<String> requiredNonNullFieldsForNullConstantPath;
      final @Nullable ReferenceTest referenceTest;

      FlowValue(final FlowValue source, final @Nullable ReferenceTest referenceTest) {
         this(source, source.parameterLocalSlots, source.nullness, source.nullConstantPath, source.mayHaveNonConstantNullPath,
            source.privateThisFieldKey, source.requiredNonNullFieldsForNullConstantPath, referenceTest);
      }

      FlowValue(final SourceValue source, final Set<Integer> parameterLocalSlots, final FlowNullness nullness,
            final boolean nullConstantPath, final boolean mayHaveNonConstantNullPath, final @Nullable String privateThisFieldKey,
            final Set<String> requiredNonNullFieldsForNullConstantPath) {
         this(source, parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath, privateThisFieldKey,
            requiredNonNullFieldsForNullConstantPath, null);
      }

      // Keep the immutable analysis facts explicit; grouping them only to shorten this internal constructor would hide their independence.
      // CHECKSTYLE:IGNORE ParameterNumber FOR NEXT 3 LINES
      FlowValue(final SourceValue source, final Set<Integer> parameterLocalSlots, final FlowNullness nullness,
            final boolean nullConstantPath, final boolean mayHaveNonConstantNullPath, final @Nullable String privateThisFieldKey,
            final Set<String> requiredNonNullFieldsForNullConstantPath, final @Nullable ReferenceTest referenceTest) {
         super(source.size, source.insns);
         this.parameterLocalSlots = Set.copyOf(parameterLocalSlots);
         this.nullness = nullness;
         this.nullConstantPath = nullConstantPath;
         this.mayHaveNonConstantNullPath = mayHaveNonConstantNullPath;
         this.privateThisFieldKey = privateThisFieldKey;
         this.requiredNonNullFieldsForNullConstantPath = Set.copyOf(requiredNonNullFieldsForNullConstantPath);
         this.referenceTest = referenceTest;
      }

      @Override
      public boolean equals(final @Nullable Object obj) {
         if (this == obj)
            return true;
         if (!(obj instanceof FlowValue))
            return false;
         final FlowValue other = (FlowValue) obj;
         return mayHaveNonConstantNullPath == other.mayHaveNonConstantNullPath && nullConstantPath == other.nullConstantPath
               && nullness == other.nullness && Objects.equals(referenceTest, other.referenceTest) && Objects.equals(privateThisFieldKey,
                  other.privateThisFieldKey) && parameterLocalSlots.equals(other.parameterLocalSlots)
               && requiredNonNullFieldsForNullConstantPath.equals(other.requiredNonNullFieldsForNullConstantPath) && super.equals(other);
      }

      @Override
      public int hashCode() {
         return Objects.hash(super.hashCode(), parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath,
            privateThisFieldKey, requiredNonNullFieldsForNullConstantPath, referenceTest);
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
                        privateThisFieldKey, refinedRequiredFields, referenceTest);
      }

      FlowValue withNullConstantRequirements(final Set<String> requiredFields) {
         return requiredNonNullFieldsForNullConstantPath.equals(requiredFields) ? this
               : new FlowValue(this, parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath, privateThisFieldKey,
                  requiredFields, referenceTest);
      }

      FlowValue withPrivateThisFieldKey(final String fieldKey) {
         return new FlowValue(this, parameterLocalSlots, nullness, nullConstantPath, mayHaveNonConstantNullPath, fieldKey,
            requiredNonNullFieldsForNullConstantPath, referenceTest);
      }
   }

   private static final class FlowFrame extends Frame<SourceValue> {
      private @Nullable FlowFrame unrefinedJumpFrame;
      private @Nullable SourceValue testedValue;
      private @Nullable ReferencePredicate testedPredicate;
      private int testedOpcode = -1;
      private boolean reachable = true;
      private final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction;
      private final NonReturningCallResolver nonReturningCallResolver;

      FlowFrame(final int numLocals, final int numStack, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction,
            final NonReturningCallResolver nonReturningCallResolver) {
         super(numLocals, numStack);
         this.knownNonNullFieldsAtInstruction = knownNonNullFieldsAtInstruction;
         this.nonReturningCallResolver = nonReturningCallResolver;
      }

      FlowFrame(final Frame<? extends SourceValue> source, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction,
            final NonReturningCallResolver nonReturningCallResolver) {
         super(source);
         this.knownNonNullFieldsAtInstruction = knownNonNullFieldsAtInstruction;
         this.nonReturningCallResolver = nonReturningCallResolver;
         if (source instanceof FlowFrame) {
            final FlowFrame sourceFlowFrame = (FlowFrame) source;
            reachable = sourceFlowFrame.reachable;
         }
         // Branch state belongs only to the temporary frame executing that instruction and must not follow frame copies.
      }

      @Override
      @SuppressWarnings("null")
      @NonNullByDefault({})
      public Frame<@NonNull SourceValue> init(final Frame<? extends SourceValue> source) {
         super.init(source);
         /* ASM reuses one working frame and resets it through init() before executing each instruction. Copy our
          * reachability state as well so a previously processed dead path cannot make an unrelated path look dead. */
         reachable = !(source instanceof FlowFrame) || ((FlowFrame) source).reachable;
         return this;
      }

      @Override
      @SuppressWarnings("null")
      @NonNullByDefault({})
      public void execute(final AbstractInsnNode instruction, final Interpreter<@NonNull SourceValue> interpreter)
            throws AnalyzerException {
         testedOpcode = -1;
         testedValue = null;
         testedPredicate = null;
         unrefinedJumpFrame = null;

         final int opcode = instruction.getOpcode();
         if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && getStackSize() > 0) {
            testedOpcode = opcode;
            testedValue = getStack(getStackSize() - 1);
            testedPredicate = opcode == Opcodes.IFNULL ? ReferencePredicate.IS_NULL : ReferencePredicate.NON_NULL;
         } else if ((opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) && getStackSize() > 0) {
            final SourceValue condition = getStack(getStackSize() - 1);
            if (condition instanceof FlowValue) {
               final ReferenceTest test = ((FlowValue) condition).referenceTest;
               if (test != null) {
                  testedOpcode = opcode;
                  testedValue = test.operand;
                  testedPredicate = test.predicate;
               }
            }
         } else if ((opcode == Opcodes.IF_ACMPEQ || opcode == Opcodes.IF_ACMPNE) && getStackSize() > 1) {
            final SourceValue first = getStack(getStackSize() - 2);
            final SourceValue second = getStack(getStackSize() - 1);
            if (first instanceof FlowValue && second instanceof FlowValue) {
               final FlowNullness firstNullness = ((FlowValue) first).nullness;
               final FlowNullness secondNullness = ((FlowValue) second).nullness;
               /* Prefer a known-null operand: it establishes facts on both edges. A known non-null operand only proves
                * the other reference non-null on equality; inequality is not a null test. */
               if (firstNullness == FlowNullness.DEFINITELY_NULL || secondNullness == FlowNullness.DEFINITELY_NULL) {
                  testedValue = firstNullness == FlowNullness.DEFINITELY_NULL ? second : first;
                  testedPredicate = ReferencePredicate.IS_NULL;
               } else if (firstNullness == FlowNullness.NEVER_NULL || secondNullness == FlowNullness.NEVER_NULL) {
                  testedValue = firstNullness == FlowNullness.NEVER_NULL ? second : first;
                  testedPredicate = ReferencePredicate.NON_NULL_ON_TRUE;
               }
               if (testedValue != null) {
                  testedOpcode = opcode;
               }
            }
         }

         final SourceValue checkedValue = determineNullCheckedValue(instruction, this);
         super.execute(instruction, interpreter);
         if (checkedValue != null) {
            /* Capture the consumed operand before execution, but refine only the normal continuation. ASM constructs
             * handler frames from the incoming state, where the checked reference may still be null. */
            refineNullness(checkedValue, FlowNullness.NEVER_NULL);
         }
         if (instruction instanceof MethodInsnNode && nonReturningCallResolver.isNonReturning((MethodInsnNode) instruction)) {
            /* ASM builds exception-handler frames from the pre-invocation state. Marking this post-invocation frame dead
             * therefore suppresses only the impossible normal continuation. */
            reachable = false;
         }
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
            unrefinedJumpFrame = new FlowFrame(this, knownNonNullFieldsAtInstruction, nonReturningCallResolver);
         }
      }

      @Override
      @NonNullByDefault({})
      public void initJumpTarget(final int opcode, final org.objectweb.asm.tree.LabelNode target) {
         final FlowFrame unrefinedFrame = unrefinedJumpFrame;
         final SourceValue tested = testedValue;
         final ReferencePredicate predicate = testedPredicate;
         if (opcode != testedOpcode || unrefinedFrame == null || predicate == null || !(tested instanceof FlowValue)) {
            super.initJumpTarget(opcode, target);
            return;
         }

         /* initJumpTarget is invoked once per normal edge. Restore the post-execution state so a refinement for the
          * fallthrough edge cannot leak into the jump edge. Exception handlers use ASM's separate pre-execution frame. */
         init(unrefinedFrame);
         super.initJumpTarget(opcode, target);

         refineNullness(tested, predicate.edgeNullness(opcode, target != null));
      }

      private void refineNullness(final SourceValue tested, final FlowNullness refinedNullness) {
         if (refinedNullness == FlowNullness.UNKNOWN || !(tested instanceof FlowValue))
            return;
         final boolean isNullEdge = refinedNullness == FlowNullness.DEFINITELY_NULL;
         final FlowValue testedFlowValue = (FlowValue) tested;
         final boolean impossibleEdge = isNullEdge ? testedFlowValue.nullness == FlowNullness.NEVER_NULL
               : testedFlowValue.nullness == FlowNullness.DEFINITELY_NULL;
         if (impossibleEdge) {
            // Structural control flow retains this edge even when its required nullness contradicts the actual value.
            reachable = false;
            return;
         }

         final FlowValue refinedValue = testedFlowValue.withNullness(refinedNullness);
         if (refinedValue == tested)
            // Most receiver accesses already have a non-null value, so avoid scanning the frame again without a change.
            return;
         // Only surviving aliases inherit the fact; a later field read or replacement local is an independent value.
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
               return true;
            }
         }
         if (!(interpreter instanceof FlowInterpreter))
            // Completion analysis also uses this frame, but its plain SourceValues carry no value-alias contracts.
            return super.merge(incoming, interpreter);
         if (getStackSize() != incoming.getStackSize())
            throw new AnalyzerException(null, "Incompatible stack heights");

         /* Pointwise value equality is weaker than aliasing: a producer-set union can reuse either operand without
          * proving that two slots hold the same reference. Intersect the identity pairs from both incoming frames. */
         final Map<FlowValue, Map<FlowValue, FlowValue>> valuesByInputs = new IdentityHashMap<>();
         final Set<FlowValue> claimedValues = Collections.newSetFromMap(new IdentityHashMap<>());
         boolean changed = false;
         for (int index = 0; index < getLocals() + getStackSize(); index++) {
            final boolean local = index < getLocals();
            final int stackIndex = index - getLocals();
            final FlowValue previous = (FlowValue) (local ? getLocal(index) : getStack(stackIndex));
            // FlowInterpreter initializes every active slot; unused locals contain an explicit unknown value.
            final FlowValue next = (FlowValue) Objects.requireNonNull(local ? incoming.getLocal(index) : incoming.getStack(stackIndex));
            final FlowValue merged = mergeValue(previous, next, interpreter, valuesByInputs, claimedValues);
            if (merged != previous) {
               if (local) {
                  setLocal(index, merged);
               } else {
                  setStack(stackIndex, merged);
               }
               // Losing an alias must revisit successors even when all ordinary FlowValue facts remain equal.
               changed = true;
            }
         }
         return changed;
      }

      private static FlowValue mergeValue(final FlowValue first, final FlowValue second, final Interpreter<SourceValue> interpreter,
            final Map<FlowValue, Map<FlowValue, FlowValue>> valuesByInputs, final Set<FlowValue> claimedValues) {
         final Map<FlowValue, FlowValue> valuesBySecond = valuesByInputs.computeIfAbsent(first, unused -> new IdentityHashMap<>());
         final FlowValue cached = valuesBySecond.get(second);
         if (cached != null)
            return cached;

         // Reusing stable fact values lets unchanged alias groups converge when loop edges are visited again.
         FlowValue merged = (FlowValue) interpreter.merge(first, second);
         final ReferenceTest test = merged.referenceTest;
         if (test != null) {
            /* The interpreter retains a predicate only when both inputs capture the identical reference. Include that
             * hidden reference in the same merge: otherwise a split local could inherit another local's saved test. */
            final FlowValue operand = mergeValue(test.operand, test.operand, interpreter, valuesByInputs, claimedValues);
            if (operand != test.operand) {
               merged = new FlowValue(merged, new ReferenceTest(operand, test.predicate));
            }
         }
         if (!claimedValues.add(merged)) {
            // Different input pairs cannot acquire a shared identity merely because the abstract facts coincide.
            merged = new FlowValue(merged, merged.referenceTest);
            claimedValues.add(merged);
         }
         valuesBySecond.put(second, merged);
         return merged;
      }
   }

   private static final class FlowAnalyzer extends Analyzer<SourceValue> {
      private final Set<TryCatchBlockNode> ignoredExceptionHandlers;
      private final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction;
      private final NonReturningCallResolver nonReturningCallResolver;

      FlowAnalyzer(final Interpreter<SourceValue> interpreter, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction) {
         this(interpreter, knownNonNullFieldsAtInstruction, Set.of(), BytecodeAnalyzer::isIntrinsicNonReturningCall);
      }

      FlowAnalyzer(final Interpreter<SourceValue> interpreter, final Map<AbstractInsnNode, Set<String>> knownNonNullFieldsAtInstruction,
            final Set<TryCatchBlockNode> ignoredExceptionHandlers, final NonReturningCallResolver nonReturningCallResolver) {
         super(interpreter);
         this.knownNonNullFieldsAtInstruction = knownNonNullFieldsAtInstruction;
         this.ignoredExceptionHandlers = ignoredExceptionHandlers;
         this.nonReturningCallResolver = nonReturningCallResolver;
      }

      @Override
      protected Frame<SourceValue> newFrame(final int numLocals, final int numStack) {
         return new FlowFrame(numLocals, numStack, knownNonNullFieldsAtInstruction, nonReturningCallResolver);
      }

      @Override
      @SuppressWarnings("null")
      protected Frame<SourceValue> newFrame(final @NonNullByDefault({}) Frame<? extends SourceValue> frame) {
         return new FlowFrame(frame, knownNonNullFieldsAtInstruction, nonReturningCallResolver);
      }

      @Override
      @NonNullByDefault({})
      protected boolean newControlFlowExceptionEdge(final int instructionIndex, final TryCatchBlockNode tryCatchBlock) {
         /* ASM conservatively connects every protected instruction to every matching handler. Omit only handlers whose
          * checked exception is impossible under a proof established before this analysis. */
         return !ignoredExceptionHandlers.contains(tryCatchBlock) && super.newControlFlowExceptionEdge(instructionIndex, tryCatchBlock);
      }
   }

   /* A proven summary means every normal reference return is either non-null or depends only on the listed parameters.
    * hasNonNullReturn distinguishes a real non-null producer from EMPTY, which is only the identity for provenance traversal. */
   private static final class DependencySummary {
      static final DependencySummary EMPTY = new DependencySummary(true, false, Set.of());
      static final DependencySummary NON_NULL = new DependencySummary(true, true, Set.of());
      static final DependencySummary UNKNOWN = new DependencySummary(false, false, Set.of());

      final boolean hasNonNullReturn;
      final Set<Integer> parameterIndexes;
      final boolean proven;
      final @Nullable Integer exactReturnedParameterIndex;

      private DependencySummary(final boolean proven, final boolean hasNonNullReturn, final Set<Integer> parameterIndexes) {
         this(proven, hasNonNullReturn, parameterIndexes, null);
      }

      private DependencySummary(final boolean proven, final boolean hasNonNullReturn, final Set<Integer> parameterIndexes,
            final @Nullable Integer exactReturnedParameterIndex) {
         this.proven = proven;
         this.hasNonNullReturn = hasNonNullReturn;
         this.parameterIndexes = Set.copyOf(parameterIndexes);
         this.exactReturnedParameterIndex = exactReturnedParameterIndex;
      }

      static DependencySummary dependentOn(final Set<Integer> parameterIndexes) {
         // A possibly-null value without a proven parameter dependency is unknown, not unconditionally non-null.
         if (parameterIndexes.isEmpty())
            return UNKNOWN;
         return new DependencySummary(true, false, parameterIndexes);
      }

      DependencySummary merge(final DependencySummary other) {
         if (!proven || !other.proven)
            return UNKNOWN;
         if (this == EMPTY)
            return other;
         if (other == EMPTY)
            return this;

         final Set<Integer> mergedIndexes = new HashSet<>(parameterIndexes);
         // A null value from either producer still implies that at least one parameter in the union was null.
         mergedIndexes.addAll(other.parameterIndexes);
         if (mergedIndexes.isEmpty())
            return NON_NULL;
         return new DependencySummary(true, hasNonNullReturn || other.hasNonNullReturn, mergedIndexes);
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
         return value.insns.isEmpty() ? terminal(DependencySummary.UNKNOWN) : new DependencyExpansion(DependencySummary.EMPTY, value.insns);
      }

      static DependencyExpansion forwarded(final Set<AbstractInsnNode> dependencies, final DependencySummary initialSummary) {
         return dependencies.isEmpty() ? terminal(DependencySummary.UNKNOWN) : new DependencyExpansion(initialSummary, dependencies);
      }

      static DependencyExpansion terminal(final DependencySummary summary) {
         return new DependencyExpansion(summary, null);
      }
   }

   private static final class DependencyFrame {
      final Iterator<AbstractInsnNode> dependencies;
      DependencySummary result;
      final AbstractInsnNode source;

      DependencyFrame(final AbstractInsnNode source, final Set<AbstractInsnNode> dependencies, final DependencySummary initialSummary) {
         this.source = source;
         this.dependencies = dependencies.iterator();
         result = initialSummary;
      }
   }

   private static final class MethodSummaryDepthExceededException extends RuntimeException {
      private static final long serialVersionUID = 1L;
   }

   private enum CompletionEvidence {
      PROVEN_NON_RETURNING,
      NOT_PROVEN,
      INCONCLUSIVE
   }

   private enum ReturnEvidence {
      PROVEN_NULL,
      PROVEN_NON_NULL,
      UNKNOWN
   }

   /** Carries return and receiver-type evidence with the entry-value identity that ordinary producer sets lose at merges. */
   private static final class ReturnFlowFacts {
      static final ReturnFlowFacts UNKNOWN = new ReturnFlowFacts(ReturnEvidence.UNKNOWN, Set.of(), Set.of(), Set.of(), null);

      final ReturnEvidence evidence;
      final Set<AbstractInsnNode> provenNonNullLoads;
      final Set<AbstractInsnNode> loadsRetainingEntryParameter;
      final Set<AbstractInsnNode> exactReceiverCalls;
      final @Nullable Integer exactReturnedParameterIndex;

      ReturnFlowFacts(final ReturnEvidence evidence, final Set<AbstractInsnNode> provenNonNullLoads,
            final Set<AbstractInsnNode> loadsRetainingEntryParameter, final Set<AbstractInsnNode> exactReceiverCalls,
            final @Nullable Integer exactReturnedParameterIndex) {
         this.evidence = evidence;
         this.provenNonNullLoads = Set.copyOf(provenNonNullLoads);
         this.loadsRetainingEntryParameter = Set.copyOf(loadsRetainingEntryParameter);
         this.exactReceiverCalls = Set.copyOf(exactReceiverCalls);
         this.exactReturnedParameterIndex = exactReturnedParameterIndex;
      }
   }

   /**
    * Carries recursion depth and a shared work allowance, keeping traversal-limited results out of reusable summaries.
    */
   private static final class ParameterAnalysisContext {
      final int remainingDepth;
      final AnalysisWorkBudget budget;
      boolean cacheable = true;

      ParameterAnalysisContext(final int remainingDepth, final AnalysisWorkBudget budget) {
         // Parameter recursion must not consume the depth budget used to establish non-returning calls or return values.
         this.remainingDepth = remainingDepth;
         this.budget = budget;
      }
   }

   /**
    * Retains a complete parameter proof and its logical work cost so cache warmth cannot expand a caller's allowance.
    */
   private static final class ParameterSummary {
      final MethodParameterAnalysis analysis;
      final long work;

      ParameterSummary(final MethodParameterAnalysis analysis, final long work) {
         this.analysis = analysis;
         this.work = work;
      }
   }

   private static final class ParameterFlowFacts {
      final Set<Integer> definitelyNullableParameters;
      final List<Set<Integer>> guaranteedNonNullParameters;
      final List<Set<Integer>> guaranteedNullParameters;

      ParameterFlowFacts(final List<Set<Integer>> guaranteedNullParameters, final List<Set<Integer>> guaranteedNonNullParameters,
            final Set<Integer> definitelyNullableParameters) {
         this.guaranteedNullParameters = guaranteedNullParameters;
         this.guaranteedNonNullParameters = guaranteedNonNullParameters;
         this.definitelyNullableParameters = definitelyNullableParameters;
      }
   }

   private static final class MethodAnalysis {
      final Set<Integer> definitelyNullableParameters;
      final Frame<SourceValue>[] frames;
      final List<Set<Integer>> guaranteedNonNullParameters;
      final List<Set<Integer>> guaranteedNullParameters;
      final Map<AbstractInsnNode, Integer> instructionIndexes;
      final AbstractInsnNode[] instructions;
      final ReturnFlowFacts returnFlowFacts;
      final Map<Integer, Integer> referenceParameterIndexesByLocalSlot;

      MethodAnalysis(final AbstractInsnNode[] instructions, final Frame<SourceValue>[] frames,
            final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot,
            final ParameterFlowFacts parameterFlowFacts, final ReturnFlowFacts returnFlowFacts) {
         this.instructions = instructions;
         this.frames = frames;
         this.instructionIndexes = instructionIndexes;
         this.referenceParameterIndexesByLocalSlot = referenceParameterIndexesByLocalSlot;
         guaranteedNullParameters = parameterFlowFacts.guaranteedNullParameters;
         guaranteedNonNullParameters = parameterFlowFacts.guaranteedNonNullParameters;
         definitelyNullableParameters = parameterFlowFacts.definitelyNullableParameters;
         this.returnFlowFacts = returnFlowFacts;
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
      private final Map<String, CompletionEvidence> completionSummaries = new HashMap<>();
      private final Map<String, DependencySummary> methodSummaries = new HashMap<>();
      private final Map<String, ParameterSummary> parameterSummaries = new HashMap<>();
      private final Map<String, Boolean> packagePrivateMethodsHaveClosedDispatch = new HashMap<>();
      private final Set<String> completionSummariesBeingComputed = new HashSet<>();
      private final Set<String> methodsBeingSummarized = new HashSet<>();
      private final Set<String> parameterSummariesBeingComputed = new HashSet<>();
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
      private MethodParameterAnalysis determineParameterSummary(final ClassNode owner, final MethodNode method,
            final ParameterAnalysisContext context) {
         final String key = owner.name + '\0' + methodKey(method.name, method.desc);
         // Check cycles before cache lookup: a warm summary must not bypass the active-call boundary of a cold traversal.
         if (!parameterSummariesBeingComputed.add(key)) {
            context.cacheable = false;
            return MethodParameterAnalysis.EMPTY;
         }
         try {
            /* At the depth boundary, skipped descendants can hide a cycle to a future caller. Retain local facts for
             * this traversal, but do not let that partial proof or its ancestors bypass the cycle check in a later run. */
            context.cacheable &= context.remainingDepth > 0;
            /* Depth is part of the proof's inputs. A summary computed near a root cannot authorize a deeper call that
             * would exhaust its budget without that cached result. */
            final String cacheKey = key + '\0' + context.remainingDepth;
            final ParameterSummary cached = parameterSummaries.get(cacheKey);
            if (cached != null && cached.work <= context.budget.remainingWork) {
               // Replay the complete cost, including descendants, instead of letting a cache hit authorize extra proof.
               context.budget.tryConsume(cached.work);
               return cached.analysis;
            }

            // Snapshot before charging or descending: both operations mutate the shared allowance used by the cached cost.
            final long workBefore = context.budget.remainingWork; // CHECKSTYLE:IGNORE MoveVariableInsideIf
            if (!context.budget.tryConsume(determineMethodAnalysisWork(method))) {
               context.cacheable = false;
               return MethodParameterAnalysis.EMPTY;
            }
            /* A cached proof that does not fit must be recomputed within the remaining allowance. Declining it outright
             * would discard local evidence that a cold traversal can still establish before its descendants are cut off. */
            final MethodParameterAnalysis result = new BytecodeAnalyzer(owner, this).determineMethodParameterAnalysis(method, context);
            if (context.cacheable) {
               parameterSummaries.put(cacheKey, new ParameterSummary(result, workBefore - context.budget.remainingWork));
            }
            return result;
         } finally {
            parameterSummariesBeingComputed.remove(key);
         }
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
            if (resolvedSummary.proven) {
               /* Cache dependencies in the callee's descriptor coordinates. Each caller maps them to its own argument
                * producers, so a constant argument at one call site cannot strengthen another caller's contract. */
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

      @SuppressWarnings("null")
      private CompletionEvidence determineCompletionEvidence(final ClassNode resolvedOwner, final MethodNode resolvedMethod) {
         final String key = resolvedOwner.name + '\0' + methodKey(resolvedMethod.name, resolvedMethod.desc);
         final CompletionEvidence cached = completionSummaries.get(key);
         if (cached != null)
            return cached;
         if ((resolvedMethod.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0) {
            completionSummaries.put(key, CompletionEvidence.NOT_PROVEN);
            return CompletionEvidence.NOT_PROVEN;
         }
         boolean hasNormalReturnInstruction = false;
         for (final AbstractInsnNode instruction : resolvedMethod.instructions) {
            if (isNormalReturnInstruction(instruction.getOpcode())) {
               hasNormalReturnInstruction = true;
               break;
            }
         }
         if (!hasNormalReturnInstruction) {
            // A concrete JVM method without a return opcode cannot complete normally, so no dataflow pass is needed.
            completionSummaries.put(key, CompletionEvidence.PROVEN_NON_RETURNING);
            return CompletionEvidence.PROVEN_NON_RETURNING;
         }
         if (!isWithinAnalysisBudget(resolvedMethod)) {
            completionSummaries.put(key, CompletionEvidence.NOT_PROVEN);
            return CompletionEvidence.NOT_PROVEN;
         }
         if (!completionSummariesBeingComputed.add(key))
            // A recursive cycle needs a fixed point. Treating its partial result as terminal would make callers unsound.
            return CompletionEvidence.INCONCLUSIVE;

         CompletionEvidence result = CompletionEvidence.INCONCLUSIVE;
         boolean enteredDepthBudget = false;
         try {
            enterMethodSummary();
            enteredDepthBudget = true;
            try {
               result = new BytecodeAnalyzer(resolvedOwner, this).determineNormalCompletionEvidence(resolvedMethod);
            } catch (final AnalyzerException ex) {
               // Terminal-call analysis contributes only positive evidence; unsupported bytecode leaves the call returning.
               System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING, "Failed to analyze normal completion of "
                     + resolvedOwner.name + "." + resolvedMethod.name + resolvedMethod.desc, ex);
               result = CompletionEvidence.NOT_PROVEN;
            }
         } catch (final MethodSummaryDepthExceededException ex) {
            /* Depth exhaustion depends on the current traversal. Do not cache it, so a later shallow lookup can still
             * establish the same helper as terminal. */
            result = CompletionEvidence.INCONCLUSIVE;
         } finally {
            if (enteredDepthBudget) {
               exitMethodSummary();
            }
            completionSummariesBeingComputed.remove(key);
         }
         if (result != CompletionEvidence.INCONCLUSIVE) {
            completionSummaries.put(key, result);
         }
         return result;
      }

      @SuppressWarnings("null")
      private boolean hasClosedPackagePrivateDispatch(final ClassNode resolvedOwner, final MethodNode resolvedMethod) {
         final String key = resolvedOwner.name + '\0' + methodKey(resolvedMethod.name, resolvedMethod.desc);
         final Boolean cached = packagePrivateMethodsHaveClosedDispatch.get(key);
         if (cached != null)
            return cached;

         final @Nullable ScanResult availableScanResult = scanResult;
         final @Nullable ClassInfo ownerInfo = availableScanResult == null ? null
               : availableScanResult.getClassInfo(resolvedOwner.name.replace('/', '.'));
         if (ownerInfo == null) {
            packagePrivateMethodsHaveClosedDispatch.put(key, Boolean.FALSE);
            return false;
         }

         final int packageSeparator = resolvedOwner.name.lastIndexOf('/');
         final String ownerPackage = packageSeparator < 0 ? "" : resolvedOwner.name.substring(0, packageSeparator);
         for (final ClassInfo subclassInfo : ownerInfo.getSubclasses()) {
            final String subclassOwner = subclassInfo.getName().replace('.', '/');
            final int subclassPackageSeparator = subclassOwner.lastIndexOf('/');
            final String subclassPackage = subclassPackageSeparator < 0 ? "" : subclassOwner.substring(0, subclassPackageSeparator);
            if (!ownerPackage.equals(subclassPackage)) {
               continue;
            }

            final @Nullable ClassNode subclass = resolveClass(subclassOwner);
            if (subclass == null) {
               packagePrivateMethodsHaveClosedDispatch.put(key, Boolean.FALSE);
               return false;
            }
            final @Nullable MethodNode override = findMethodNode(subclass, resolvedMethod.name, resolvedMethod.desc);
            if (override != null && (override.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC)) == 0) {
               packagePrivateMethodsHaveClosedDispatch.put(key, Boolean.FALSE);
               return false;
            }
         }
         /* Package-private dispatch is closed only relative to the accepted package scan. This supports library-internal
          * guards such as SWT Widget.error(...) without assuming that public or protected virtual calls are closed. */
         packagePrivateMethodsHaveClosedDispatch.put(key, Boolean.TRUE);
         return true;
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

   private static long determineMethodAnalysisWork(final MethodNode method) {
      // Match the frame and handler dimensions of the per-method limits; even an empty declaration costs one lookup.
      return Math.max(1, method.instructions.size()) * Math.max(1L, (long) method.maxLocals + method.maxStack + method.tryCatchBlocks
         .size());
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
         final AnalysisWorkBudget budget) {
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
         final Map<AbstractInsnNode, String> testedPrivateFields, final AnalysisWorkBudget budget) throws AnalyzerException {
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
         final Map<AbstractInsnNode, FlowValue> testedValues, final AnalysisWorkBudget budget) throws AnalyzerException {
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

   private static boolean isIntrinsicNonReturningCall(final MethodInsnNode call) {
      /* Match the complete Unsafe invocation contract. A method name such as throwException alone cannot establish
       * that arbitrary application code has no normal return. */
      return call.getOpcode() == Opcodes.INVOKEVIRTUAL && "jdk/internal/misc/Unsafe".equals(call.owner) && "throwException".equals(
         call.name) && "(Ljava/lang/Throwable;)V".equals(call.desc);
   }

   private static boolean isPackagePrivate(final int access) {
      return (access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_PROTECTED | Opcodes.ACC_PRIVATE)) == 0;
   }

   private boolean hasExactSpecialTarget(final MethodInsnNode call) {
      /* Constructors and same-class references select the named declaration. Other superclass calls can select an
       * intermediate declaration, even for a private method accessible through the same nest. All body-derived proofs
       * therefore stop at the immediate superclass instead of trusting an indirect symbolic ancestor. */
      return call.getOpcode() != Opcodes.INVOKESPECIAL || call.name.equals("<init>") || call.owner.equals(classNode.name) || call.owner
         .equals(classNode.superName);
   }

   private boolean hasExactDeclaredTarget(final MethodInsnNode call, final ClassNode owner, final MethodNode method) {
      if (!hasExactSpecialTarget(call))
         return false;
      final int opcode = call.getOpcode();
      return opcode == Opcodes.INVOKESTATIC || opcode == Opcodes.INVOKESPECIAL || (owner.access & Opcodes.ACC_FINAL) != 0 || (method.access
            & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL)) != 0;
   }

   private boolean isProvenNonReturningCall(final MethodInsnNode call) {
      if (isIntrinsicNonReturningCall(call))
         return true;

      @SuppressWarnings("null")
      final @Nullable ClassNode resolvedOwner = call.owner.equals(classNode.name) ? classNode
            : methodSummaryResolver.resolveClass(call.owner);
      if (resolvedOwner == null)
         return false;

      @SuppressWarnings("null")
      final @Nullable MethodNode resolvedMethod = findMethodNode(resolvedOwner, call.name, call.desc);
      if (resolvedMethod == null)
         // Full JVM method resolution is intentionally outside this optional proof; analyze exact declarations only.
         return false;

      if (!hasExactDeclaredTarget(call, resolvedOwner, resolvedMethod)) {
         if (call.getOpcode() != Opcodes.INVOKEVIRTUAL || !call.owner.equals(classNode.name) || !isPackagePrivate(resolvedMethod.access)
               || !methodSummaryResolver.hasClosedPackagePrivateDispatch(resolvedOwner, resolvedMethod))
            /* Public and protected virtual calls remain open to consumer subclasses. A package-private helper is safe
             * only in its declaring class when the accepted package contains no overriding subclass. */
            return false;
      }
      return methodSummaryResolver.determineCompletionEvidence(resolvedOwner, resolvedMethod) == CompletionEvidence.PROVEN_NON_RETURNING;
   }

   private CompletionEvidence determineNormalCompletionEvidence(final MethodNode method) throws AnalyzerException {

      @SuppressWarnings("null")
      final AbstractInsnNode[] instructions = method.instructions.toArray();

      @SuppressWarnings("null")
      final Frame<SourceValue>[] frames = new FlowAnalyzer(new SourceInterpreter(), Map.of(), Set.of(), this::isProvenNonReturningCall)
         .analyze(classNode.name, method);

      for (int i = 0; i < instructions.length; i++) {
         if (isNormalReturnInstruction(instructions[i].getOpcode()) && isReachableFrame(frames[i]))
            return CompletionEvidence.NOT_PROVEN;
      }
      return CompletionEvidence.PROVEN_NON_RETURNING;
   }

   private static boolean isKnownNonNullMethod(final int opcode, final String clazz, final String methodName, final String descriptor) {
      // Share exact factory contracts with static-field provenance so both passes agree on normal results.
      if (isKnownNonNullStaticFactory(opcode, clazz, methodName, descriptor))
         return true;

      if (opcode == Opcodes.INVOKEVIRTUAL && "newInstance".equals(methodName)) {
         /* Class.newInstance() uses Constructor directly on Java 11 and ReflectionFactory on newer JDKs. Both calls
          * either fail or return the newly created instance; successful construction cannot produce null. */
         if ("java/lang/reflect/Constructor".equals(clazz) && "([Ljava/lang/Object;)Ljava/lang/Object;".equals(descriptor))
            return true;
         if ("jdk/internal/reflect/ReflectionFactory".equals(clazz)
               && "(Ljava/lang/reflect/Constructor;[Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;".equals(descriptor))
            return true;
      }

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

   private static boolean isKnownNonNullDynamicFactory(final InvokeDynamicInsnNode instruction) {
      final int returnSort = Type.getReturnType(instruction.desc).getSort();
      if (returnSort != Type.OBJECT && returnSort != Type.ARRAY || instruction.bsm.getTag() != Opcodes.H_INVOKESTATIC)
         return false;

      final String owner = instruction.bsm.getOwner();
      final String name = instruction.bsm.getName();
      final String descriptor = instruction.bsm.getDesc();
      /* The bootstrap identifies the factory; the invokedynamic name identifies its functional method. A non-null
       * lambda object says nothing about the value returned when that functional method is invoked later. */
      if ("java/lang/invoke/LambdaMetafactory".equals(owner))
         return "metafactory".equals(name) && ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
               + "Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;"
               + "Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;").equals(descriptor) || "altMetafactory".equals(name)
                     && ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                           + "Ljava/lang/invoke/MethodType;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;").equals(descriptor);
      // Keep all dynamic-factory consumers aligned; arbitrary bootstraps may produce null even with a familiar call-site name.
      return "java/lang/invoke/StringConcatFactory".equals(owner) && ("makeConcat".equals(name)
            && "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;".equals(
               descriptor) || "makeConcatWithConstants".equals(name) && ("(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;"
                     + "Ljava/lang/invoke/MethodType;Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;").equals(
                        descriptor));
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
         // Dispatch and Cloneable evidence belong to the method's declaring class, even when it writes another nestmate's field.
         final BytecodeAnalyzer writerAnalyzer = nestClass == classNode ? this : new BytecodeAnalyzer(nestClass, methodSummaryResolver);
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
               final Frame<SourceValue>[] frames = new FlowAnalyzer(writerAnalyzer.new FlowInterpreter(false), Map.of()).analyze(
                  nestClass.name, method);
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
      private final boolean forwardNullArguments;

      FlowInterpreter(final boolean trackPrivateFieldFacts) {
         this(trackPrivateFieldFacts, false);
      }

      FlowInterpreter(final boolean trackPrivateFieldFacts, final boolean forwardNullArguments) {
         // SourceInterpreter's public constructor rejects subclasses; the protected API-level constructor is the extension point.
         super(Opcodes.ASM9);
         this.trackPrivateFieldFacts = trackPrivateFieldFacts;
         this.forwardNullArguments = forwardNullArguments;
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
            case Opcodes.INSTANCEOF:
               /* Preserve the tested reference itself, not its local slot. A saved Boolean must not refine a replacement
                * assigned to that slot later; copies retain identity and ambiguous merges discard it below. */
               return new FlowValue(source, Set.of(), FlowNullness.NEVER_NULL, false, false, null, Set.of(), new ReferenceTest(
                  (FlowValue) value, ReferencePredicate.NON_NULL_ON_TRUE));
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
            final ReferencePredicate predicate = objectsReferencePredicate(method);
            if (predicate != null)
               return new FlowValue(source, Set.of(), FlowNullness.NEVER_NULL, false, false, null, Set.of(), new ReferenceTest(Objects
                  .requireNonNull((FlowValue) values.get(0)), predicate));
            returnType = Type.getReturnType(method.desc);
            /* Object.clone() either throws or returns the newly allocated clone. Require a Cloneable receiver hierarchy as
             * well as exact invokespecial resolution so an unreachable post-call path cannot become positive evidence. */
            knownNonNull = isKnownNonNullMethod(method.getOpcode(), method.owner, method.name, method.desc) || resolvesToObjectGetClass(
               method) || resolvesToObjectClone(method) && isTransitivelyCloneable();
            if (forwardNullArguments && !knownNonNull && isReferenceType(returnType.getDescriptor())) {
               final int argumentOffset = method.getOpcode() == Opcodes.INVOKESTATIC ? 0 : 1;
               boolean hasNullArgument = false;
               for (int i = argumentOffset; i < values.size(); i++) {
                  hasNullArgument |= ((FlowValue) values.get(i)).nullConstantPath;
               }
               if (hasNullArgument) {
                  /* A PolyNull dependency is only one-way: a helper may replace null with a non-null fallback. Copy
                   * null provenance only when every return is the exact entry argument, after ordinary dispatch checks. */
                  // Frames are still widening here, so receiver-allocation proofs belong to the completed summary pass.
                  final Integer returnedArgument = determineCalledMethodDependencySummary(method, false).exactReturnedParameterIndex;
                  if (returnedArgument != null && returnedArgument + argumentOffset < values.size())
                     return values.get(returnedArgument + argumentOffset);
               }
            }
         } else {
            final InvokeDynamicInsnNode method = (InvokeDynamicInsnNode) instruction;
            returnType = Type.getReturnType(method.desc);
            knownNonNull = isKnownNonNullDynamicFactory(method);
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
         // Both reference identity and predicate polarity must agree across every incoming Boolean.
         final ReferenceTest mergedReferenceTest = Objects.equals(firstFlowValue.referenceTest, secondFlowValue.referenceTest)
               ? firstFlowValue.referenceTest
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
               && mergedNonConstantNullPath == firstFlowValue.mayHaveNonConstantNullPath && Objects.equals(mergedReferenceTest,
                  firstFlowValue.referenceTest) && Objects.equals(mergedPrivateFieldKey, firstFlowValue.privateThisFieldKey)
               && mergedSources.equals(first.insns) && mergedParameterSlots.equals(firstFlowValue.parameterLocalSlots)
               && mergedRequiredFields.equals(firstFlowValue.requiredNonNullFieldsForNullConstantPath))
            return first;
         if (mergedSize == second.size && mergedNullness == secondFlowValue.nullness
               && mergedNullConstantPath == secondFlowValue.nullConstantPath
               && mergedNonConstantNullPath == secondFlowValue.mayHaveNonConstantNullPath && Objects.equals(mergedReferenceTest,
                  secondFlowValue.referenceTest) && Objects.equals(mergedPrivateFieldKey, secondFlowValue.privateThisFieldKey)
               && mergedSources.equals(second.insns) && mergedParameterSlots.equals(secondFlowValue.parameterLocalSlots)
               && mergedRequiredFields.equals(secondFlowValue.requiredNonNullFieldsForNullConstantPath))
            return second;
         return new FlowValue(new SourceValue(mergedSize, mergedSources), mergedParameterSlots, mergedNullness, mergedNullConstantPath,
            mergedNonConstantNullPath, mergedPrivateFieldKey, mergedRequiredFields, mergedReferenceTest);
      }
   }

   private static boolean isReferenceType(final String descriptor) {
      final int sort = Type.getType(descriptor).getSort();
      return sort == Type.OBJECT || sort == Type.ARRAY;
   }

   @SuppressWarnings("null")
   private static boolean isKnownNonNullStaticFactory(final MethodInsnNode method) {
      return isKnownNonNullStaticFactory(method.getOpcode(), method.owner, method.name, method.desc);
   }

   @SuppressWarnings("null") // ASM's descriptor parser returns non-null argument types but has no nullness annotations.
   private static boolean isKnownNonNullStaticFactory(final int opcode, final String owner, final String methodName,
         final String descriptor) {
      if (isPrimitiveWrapperValueOf(opcode, owner, methodName, descriptor))
         return true;
      if (opcode != Opcodes.INVOKESTATIC)
         return false;

      final boolean isMap = "java/util/Map".equals(owner);
      if (!isMap && !"java/util/List".equals(owner) && !"java/util/Set".equals(owner))
         return false;
      if (!("L" + owner + ";").equals(Type.getReturnType(descriptor).getDescriptor()))
         return false;

      /* These exact Java 11 factories return a collection or throw, even when they reuse their input or reject null
       * elements. The contract qualifies only the returned reference, not its generic arguments or array contents. */
      final Type[] arguments = Type.getArgumentTypes(descriptor);
      if ("copyOf".equals(methodName))
         return arguments.length == 1 && (isMap ? "Ljava/util/Map;" : "Ljava/util/Collection;").equals(arguments[0].getDescriptor());
      if (isMap && "ofEntries".equals(methodName))
         return arguments.length == 1 && "[Ljava/util/Map$Entry;".equals(arguments[0].getDescriptor());
      if (!"of".equals(methodName))
         return false;
      if (!isMap && arguments.length == 1 && "[Ljava/lang/Object;".equals(arguments[0].getDescriptor()))
         return true;
      // Map.of has paired key/value arguments and no Object[] overload; List.of and Set.of accept up to ten fixed elements.
      if (arguments.length > (isMap ? 20 : 10) || isMap && arguments.length % 2 != 0)
         return false;
      for (final Type argument : arguments) {
         if (!"Ljava/lang/Object;".equals(argument.getDescriptor()))
            return false;
      }
      return true;
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
            return isKnownNonNullStaticFactory((MethodInsnNode) source) ? Set.of() : null;
         case Opcodes.INVOKEDYNAMIC:
            return isKnownNonNullDynamicFactory((InvokeDynamicInsnNode) source) ? Set.of() : null;
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
      DependencySummary result = DependencySummary.EMPTY;
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
               pendingSources.push(new DependencyFrame(rootSource, rootDependencies, rootExpansion.summary));
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
                        pendingSources.push(new DependencyFrame(dependency, Objects.requireNonNull(expansion.dependencies),
                           expansion.summary));
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

   private static boolean isExactAllocationValue(final SourceValue value, final String owner) {
      /* FlowInterpreter preserves entry values through copies and joins. A surviving parameter or this value prevents
       * an exact runtime type even when a guard proves it non-null; ordinary producer unions lose that alternative. */
      if (!(value instanceof FlowValue) || !((FlowValue) value).parameterLocalSlots.isEmpty() || value.insns.isEmpty())
         return false;
      // Flow copies and casts retain the original producers, so every remaining alternative must allocate this exact owner.
      for (final AbstractInsnNode source : value.insns) {
         if (source.getOpcode() != Opcodes.NEW || !owner.equals(((TypeInsnNode) source).desc))
            // NEW Sub / INVOKEVIRTUAL Base needs inherited-method resolution, which is outside this proof.
            return false;
      }
      return true;
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

   private static DependencySummary determineDirectParameterDependencies(final SourceValue value, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot) {
      // Return and nullable-acceptance analyses do not consume the delegated-parameter allowance.
      return determineDirectParameterDependencies(value, frames, instructionIndexes, referenceParameterIndexesByLocalSlot, null);
   }

   private static DependencySummary determineDirectParameterDependencies(final SourceValue value, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot,
         final @Nullable ParameterAnalysisContext parameterContext) {
      return determineDirectParameterDependencies(value, frames, instructionIndexes, referenceParameterIndexesByLocalSlot, parameterContext,
         false);
   }

   @SuppressWarnings("null")
   private static DependencySummary determineDirectParameterDependencies(final SourceValue value, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot,
         final @Nullable ParameterAnalysisContext parameterContext, final boolean finishLocalProof) {
      return determineDependencies(value, source -> {
         final DependencyExpansion expansion = determineDirectParameterExpansion(source, frames, instructionIndexes,
            referenceParameterIndexesByLocalSlot);
         return chargeParameterExpansion(expansion, parameterContext, finishLocalProof);
      });
   }

   private static DependencyExpansion chargeParameterExpansion(final DependencyExpansion expansion,
         final @Nullable ParameterAnalysisContext parameterContext, final boolean finishLocalProof) {
      /* Cached producers still require a visit and merge from each parent. Charge all outgoing edges before traversal
       * so shared provenance cannot hide quadratic work. Failed proofs still consume the producer's own unit. */
      final Set<AbstractInsnNode> dependencies = expansion.dependencies;
      final long work = 1L + (dependencies == null ? 0 : dependencies.size());
      if (parameterContext != null && !parameterContext.budget.tryConsume(work)) {
         // An incomplete proof must not warm a reusable helper summary; independent local facts remain available.
         parameterContext.cacheable = false;
         /* Finish an admitted method's local proof before helper traversal. Its cost still exhausts the common allowance,
          * preventing siblings or descendants from multiplying this uninterruptible fallback work. */
         if (!finishLocalProof)
            return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
      }
      return expansion;
   }

   /** Pairs parameter provenance with predicate polarity for all consumers of conditional nullness facts. */
   private static final class ParameterCheck {
      final DependencySummary dependencies;
      final ReferencePredicate predicate;

      ParameterCheck(final DependencySummary dependencies, final ReferencePredicate predicate) {
         this.dependencies = dependencies;
         this.predicate = predicate;
      }

   }

   @SuppressWarnings("null")
   private static ParameterCheck determinePredicateParameterCheck(final SourceValue condition, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot,
         final int parameterLocalSlotCount, final @Nullable ParameterAnalysisContext parameterContext) {
      /* Keep Boolean copies and reference operands in one memoized proof. Restarting at each predicate would retrace
       * shared reference aliases for every merged test; the common traversal also retains cycle and edge-work checks. */
      final Set<ReferencePredicate> predicates = new HashSet<>();
      final DependencySummary dependencies = determineDependencies(condition, source -> {
         final Integer sourceIndex = instructionIndexes.get(source);
         final Frame<SourceValue> frame = sourceIndex == null ? null : frames[sourceIndex];
         DependencyExpansion expansion = DependencyExpansion.terminal(DependencySummary.UNKNOWN);
         if (frame != null) {
            final int opcode = source.getOpcode();
            if (opcode == Opcodes.ILOAD) {
               final int localSlot = ((VarInsnNode) source).var;
               /* A Boolean parameter may keep its caller-supplied true value on a path without the assignment. Producer
                * sets omit that entry path, just as for reassigned reference parameters, so only ordinary locals qualify. */
               if (localSlot >= parameterLocalSlotCount) {
                  expansion = DependencyExpansion.forwarded(frame.getLocal(localSlot));
               }
            } else if (opcode == Opcodes.ISTORE || opcode == Opcodes.INSTANCEOF || source instanceof MethodInsnNode
                  && objectsReferencePredicate((MethodInsnNode) source) != null) {
               if (opcode == Opcodes.INSTANCEOF) {
                  predicates.add(ReferencePredicate.NON_NULL_ON_TRUE);
               } else if (source instanceof MethodInsnNode) {
                  predicates.add(Objects.requireNonNull(objectsReferencePredicate((MethodInsnNode) source)));
               }
               if (frame.getStackSize() > 0) {
                  expansion = DependencyExpansion.forwarded(frame.getStack(frame.getStackSize() - 1));
               }
            } else {
               // Valid bytecode separates Boolean and reference producers; arbitrary Boolean computations still fail this resolver.
               expansion = determineDirectParameterExpansion(source, frames, instructionIndexes, referenceParameterIndexesByLocalSlot);
            }
         }
         return chargeParameterExpansion(expansion, parameterContext, true);
      });
      // Merged opposite predicates cannot attach either polarity to their shared parameter dependency.
      return predicates.size() == 1 ? new ParameterCheck(dependencies, predicates.iterator().next())
            : new ParameterCheck(DependencySummary.UNKNOWN, ReferencePredicate.NON_NULL_ON_TRUE);
   }

   @SuppressWarnings("null")
   private static DependencyExpansion determineDirectParameterExpansion(final AbstractInsnNode source, final Frame<SourceValue>[] frames,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final Map<Integer, Integer> referenceParameterIndexesByLocalSlot) {
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
            if (parameterIndex != null)
               /* ASM represents an untouched entry parameter with no producer. After a conditional overwrite, its
                * merge keeps only the assignment producer and loses the untouched entry path. Following that producer
                * could therefore attribute a guard to the replacement parameter even when that replacement was unused. */
               return DependencyExpansion.terminal(localValue.insns.isEmpty() //
                     ? DependencySummary.dependentOn(Set.of(parameterIndex)) //
                     : DependencySummary.UNKNOWN);
            return DependencyExpansion.forwarded(localValue);
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

   private static boolean isObjectsRequireNonNull(final MethodInsnNode call) {
      if (call.getOpcode() != Opcodes.INVOKESTATIC || !call.owner.equals("java/util/Objects") || !call.name.equals("requireNonNull"))
         return false;

      // Match only the Java 11 overloads whose normal completion proves that their first argument was non-null.
      switch (call.desc) {
         case "(Ljava/lang/Object;)Ljava/lang/Object;":
         case "(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/Object;":
         case "(Ljava/lang/Object;Ljava/util/function/Supplier;)Ljava/lang/Object;":
            return true;
         default:
            return false;
      }
   }

   private static @Nullable ReferencePredicate objectsReferencePredicate(final MethodInsnNode call) {
      // Exact JDK signatures exclude application predicates and overloads with unrelated Boolean meanings.
      if (call.getOpcode() != Opcodes.INVOKESTATIC || !"java/util/Objects".equals(call.owner) || !"(Ljava/lang/Object;)Z".equals(call.desc))
         return null;
      if ("isNull".equals(call.name))
         return ReferencePredicate.IS_NULL;
      return "nonNull".equals(call.name) ? ReferencePredicate.NON_NULL : null;
   }

   private static boolean isParameterAliasSetupInstruction(final AbstractInsnNode instruction) {
      final int opcode = instruction.getOpcode();
      // These pure predicates and Boolean copies can prepare an entry guard without rejecting a null argument.
      return opcode < 0 || opcode == Opcodes.NOP || opcode == Opcodes.ALOAD || opcode == Opcodes.ASTORE || opcode == Opcodes.CHECKCAST
            || opcode == Opcodes.DUP || opcode == Opcodes.ILOAD || opcode == Opcodes.ISTORE || instruction instanceof MethodInsnNode
                  && objectsReferencePredicate((MethodInsnNode) instruction) != null;
   }

   private static boolean hasDistinctConditionalSuccessors(final int instructionIndex, final ControlFlowAnalyzer controlFlow) {
      /* Successors are stored in a set. If a jump targets its own fall-through instruction, both logical outcomes collapse
       * into one entry and no edge-specific nullness fact can be attached safely. */
      return controlFlow.normalSuccessors.get(instructionIndex).size() == 2;
   }

   private static boolean isEarlyReturnPreparationInstruction(final AbstractInsnNode instruction) {
      final int opcode = instruction.getOpcode();
      if (opcode < 0 || opcode == Opcodes.NOP || opcode == Opcodes.GOTO || opcode >= Opcodes.ACONST_NULL && opcode <= Opcodes.DCONST_1
            || opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH || opcode >= Opcodes.ILOAD && opcode <= Opcodes.ALOAD)
         return true;
      if (opcode == Opcodes.LDC)
         // A ConstantDynamic can run arbitrary bootstrap code; ordinary constants cannot turn the null arm into a rejecting path.
         return !(((LdcInsnNode) instruction).cst instanceof ConstantDynamic);
      return isNormalReturnInstruction(opcode);
   }

   private static boolean isInitialParameterGuard(final int guardIndex, final AbstractInsnNode[] instructions,
         final ControlFlowAnalyzer controlFlow) {
      final Set<Integer> visited = new HashSet<>();
      int instructionIndex = 0;
      while (instructionIndex != guardIndex) {
         if (!visited.add(instructionIndex) || !controlFlow.exceptionSuccessors.get(instructionIndex).isEmpty()
               || !isParameterAliasSetupInstruction(instructions[instructionIndex]))
            return false;

         final Set<Integer> successors = controlFlow.normalSuccessors.get(instructionIndex);
         if (successors.size() != 1)
            return false;
         instructionIndex = successors.iterator().next();
      }
      return true;
   }

   private static boolean isUnconditionalEarlyReturnPath(final int firstInstructionIndex, final AbstractInsnNode[] instructions,
         final ControlFlowAnalyzer controlFlow) {
      final Set<Integer> visited = new HashSet<>();
      int instructionIndex = firstInstructionIndex;
      while (visited.add(instructionIndex)) {
         final AbstractInsnNode instruction = instructions[instructionIndex];
         if (!controlFlow.exceptionSuccessors.get(instructionIndex).isEmpty() || !isEarlyReturnPreparationInstruction(instruction))
            return false;
         if (isNormalReturnInstruction(instruction.getOpcode()))
            return true;

         final Set<Integer> successors = controlFlow.normalSuccessors.get(instructionIndex);
         if (successors.size() != 1)
            return false;
         instructionIndex = successors.iterator().next();
      }
      return false;
   }

   @SuppressWarnings("null")
   private static Set<Integer> determineDefinitelyNullableParameters(final AbstractInsnNode[] instructions,
         final ControlFlowAnalyzer controlFlow, final Map<AbstractInsnNode, Integer> instructionIndexes,
         final Map<AbstractInsnNode, ParameterCheck> branchChecks) {
      final Set<Integer> result = new HashSet<>();
      for (int instructionIndex = 0; instructionIndex < instructions.length; instructionIndex++) {
         final AbstractInsnNode instruction = instructions[instructionIndex];
         final int opcode = instruction.getOpcode();
         final ParameterCheck check = branchChecks.get(instruction);
         if (check == null || !isInitialParameterGuard(instructionIndex, instructions, controlFlow)) {
            continue;
         }

         final DependencySummary testedValueDependencies = check.dependencies;
         if (!testedValueDependencies.proven || testedValueDependencies.parameterIndexes.size() != 1) {
            continue;
         }

         final Integer jumpTargetIndex = instructionIndexes.get(((JumpInsnNode) instruction).label);
         if (jumpTargetIndex == null || !hasDistinctConditionalSuccessors(instructionIndex, controlFlow)) {
            continue;
         }
         for (final int successor : controlFlow.normalSuccessors.get(instructionIndex)) {
            final boolean isNullEdge = check.predicate.edgeNullness(opcode, successor == jumpTargetIndex) == FlowNullness.DEFINITELY_NULL;
            if (isNullEdge && isUnconditionalEarlyReturnPath(successor, instructions, controlFlow)) {
               /* Restrict Nullable inference to a direct entry guard whose null arm cannot call, conditionally branch, or throw.
                * A later or conditional null return may be unreachable for null because earlier code rejected it. */
               result.add(testedValueDependencies.parameterIndexes.iterator().next());
            }
         }
      }
      return result;
   }

   @SuppressWarnings("null")
   private static List<Set<Integer>> determineGuaranteedNullParameters(final AbstractInsnNode[] instructions,
         final ControlFlowAnalyzer controlFlow, final Map<AbstractInsnNode, Integer> instructionIndexes,
         final Map<AbstractInsnNode, ParameterCheck> branchChecks) {
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

         final ParameterCheck check = branchChecks.get(instruction);
         final Integer jumpTargetIndex = check == null ? null : instructionIndexes.get(((JumpInsnNode) instruction).label);

         for (final int successor : controlFlow.normalSuccessors.get(instructionIndex)) {
            Set<Integer> outgoingState = incomingState;
            if (check != null && jumpTargetIndex != null && check.dependencies.proven && !check.dependencies.parameterIndexes.isEmpty()) {
               final boolean isNullEdge = check.predicate.edgeNullness(opcode,
                  successor == jumpTargetIndex) == FlowNullness.DEFINITELY_NULL;
               if (isNullEdge) {
                  outgoingState = addDependencies(incomingState, check.dependencies.parameterIndexes);
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

   private static @Nullable SourceValue determineDereferencedValue(final AbstractInsnNode instruction, final Frame<SourceValue> frame) {
      final int opcode = instruction.getOpcode();
      final int distanceFromTop;
      if (opcode == Opcodes.GETFIELD || opcode == Opcodes.ARRAYLENGTH || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
         distanceFromTop = 1;
      } else if (opcode == Opcodes.PUTFIELD || opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
         distanceFromTop = 2;
      } else if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
         distanceFromTop = 3;
      } else {
         return null;
      }
      /* ASM stores one entry per value, including long and double. Successful access checks the receiver or array;
       * it does not establish that a loaded field or array element is non-null. ATHROW has no normal continuation. */
      return frame.getStackSize() < distanceFromTop ? null : frame.getStack(frame.getStackSize() - distanceFromTop);
   }

   private static @Nullable SourceValue determineNullCheckedValue(final AbstractInsnNode instruction, final Frame<SourceValue> frame) {
      if (!(instruction instanceof MethodInsnNode))
         return determineDereferencedValue(instruction, frame);
      final MethodInsnNode call = (MethodInsnNode) instruction;
      // Receiver checks do not depend on virtual dispatch; an ordinary static argument needs its own call contract.
      if (isNullRejectingReceiverCall(call))
         return determineReceiverValue(call, frame);
      if (isObjectsRequireNonNull(call)) {
         final SourceValue[] arguments = determineArgumentValues(call, frame);
         // The checked reference supplies the fact; message arguments retain their independent contracts.
         return arguments.length == 0 ? null : arguments[0];
      }
      return null;
   }

   @SuppressWarnings("null")
   private static Map<AbstractInsnNode, ParameterCheck> determineBranchParameterChecks(final MethodNode method,
         final AbstractInsnNode[] instructions, final Frame<SourceValue>[] frames, final ControlFlowAnalyzer controlFlow,
         final Map<AbstractInsnNode, Integer> instructionIndexes, final @Nullable ParameterAnalysisContext parameterContext) {
      final Map<Integer, Integer> referenceParameterIndexesByLocalSlot = determineReferenceParameterIndexesByLocalSlot(method);
      int parameterLocalSlotCount = (method.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
      for (final Type argumentType : Type.getArgumentTypes(method.desc)) {
         // Include primitive arguments and category-2 padding when separating saved Boolean locals from entry parameters.
         parameterLocalSlotCount += argumentType.getSize();
      }
      final Map<AbstractInsnNode, ParameterCheck> result = new IdentityHashMap<>();
      for (int i = 0; i < instructions.length; i++) {
         final Frame<SourceValue> frame = frames[i];
         if (!isReachableFrame(frame) || frame.getStackSize() == 0 || !hasDistinctConditionalSuccessors(i, controlFlow))
            continue;
         final AbstractInsnNode instruction = instructions[i];
         final int opcode = instruction.getOpcode();
         final SourceValue condition = frame.getStack(frame.getStackSize() - 1);
         if (opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) {
            result.put(instruction, new ParameterCheck(determineDirectParameterDependencies(condition, frames, instructionIndexes,
               referenceParameterIndexesByLocalSlot, parameterContext, true), opcode == Opcodes.IFNULL ? ReferencePredicate.IS_NULL
                     : ReferencePredicate.NON_NULL));
         } else if (opcode == Opcodes.IFEQ || opcode == Opcodes.IFNE) {
            result.put(instruction, determinePredicateParameterCheck(condition, frames, instructionIndexes,
               referenceParameterIndexesByLocalSlot, parameterLocalSlotCount, parameterContext));
         }
      }
      return result;
   }

   @SuppressWarnings("null")
   private static Map<AbstractInsnNode, DependencySummary> determineLocalParameterChecks(final MethodNode method,
         final AbstractInsnNode[] instructions, final Frame<SourceValue>[] frames, final Map<AbstractInsnNode, Integer> instructionIndexes,
         final Set<AbstractInsnNode> instructionsProtectedByNullPointerExceptionHandler, final ParameterAnalysisContext parameterContext) {
      final Map<Integer, Integer> referenceParameterIndexesByLocalSlot = determineReferenceParameterIndexesByLocalSlot(method);
      final Map<AbstractInsnNode, DependencySummary> result = new IdentityHashMap<>();
      for (int i = 0; i < instructions.length; i++) {
         final Frame<SourceValue> frame = frames[i];
         // A terminal call can leave an allocated but unreachable successor frame; it must not spend optional work.
         if (!isReachableFrame(frame))
            continue;
         final AbstractInsnNode instruction = instructions[i];
         final SourceValue checkedValue = instructionsProtectedByNullPointerExceptionHandler.contains(instruction) ? null
               : determineNullCheckedValue(instruction, frame);
         if (checkedValue != null) {
            result.put(instruction, determineDirectParameterDependencies(checkedValue, frames, instructionIndexes,
               referenceParameterIndexesByLocalSlot, parameterContext, true));
         }
      }
      return result;
   }

   @SuppressWarnings("null")
   private List<Set<Integer>> determineGuaranteedNonNullParameters(final MethodNode method, final AbstractInsnNode[] instructions,
         final Frame<SourceValue>[] frames, final ControlFlowAnalyzer controlFlow, final Map<AbstractInsnNode, Integer> instructionIndexes,
         final Map<AbstractInsnNode, ParameterCheck> branchChecks, final @Nullable ParameterAnalysisContext parameterContext) {
      final Map<Integer, Integer> referenceParameterIndexesByLocalSlot = determineReferenceParameterIndexesByLocalSlot(method);
      // The context selects parameter evidence as well as its recursion budget; return analysis supplies no context.
      final boolean inferParameterContracts = parameterContext != null;
      final List<Set<Integer>> states = new ArrayList<>(instructions.length);
      for (int i = 0; i < instructions.length; i++) {
         states.add(Set.of());
      }
      if (instructions.length == 0)
         return states;

      final boolean[] reached = new boolean[instructions.length];
      final Deque<Integer> pendingInstructions = new ArrayDeque<>();
      final Set<AbstractInsnNode> instructionsProtectedByNullPointerExceptionHandler = inferParameterContracts
            ? determineInstructionsProtectedByNullPointerExceptionHandler(method)
            : Set.of();
      /* Local provenance depends only on the fixed ASM frames, not on the must-fact state. Compute it once, before
       * descending into any helper, so its work is charged even when recursion prevents summary caching. Retaining
       * these results also preserves local facts after delegated exhaustion and avoids retracing them on CFG revisits. */
      final Map<AbstractInsnNode, DependencySummary> localChecks = parameterContext == null ? Map.of()
            : determineLocalParameterChecks(method, instructions, frames, instructionIndexes,
               instructionsProtectedByNullPointerExceptionHandler, parameterContext);
      reached[0] = true;
      pendingInstructions.add(0);

      while (!pendingInstructions.isEmpty()) {
         final int instructionIndex = pendingInstructions.removeFirst();
         final Set<Integer> incomingState = states.get(instructionIndex);
         final AbstractInsnNode instruction = instructions[instructionIndex];
         final int opcode = instruction.getOpcode();

         Set<Integer> normalCompletionState = incomingState;
         final DependencySummary localCheck = localChecks.getOrDefault(instruction, DependencySummary.UNKNOWN);
         // Direct dereferences and calls apply after normal completion; branch checks remain edge-specific below.
         if (localCheck.proven && localCheck.parameterIndexes.size() == 1) {
            normalCompletionState = addDependencies(normalCompletionState, localCheck.parameterIndexes);
         }
         if (instruction instanceof MethodInsnNode) {
            final MethodInsnNode call = (MethodInsnNode) instruction;
            final Set<Integer> provenParameters = new HashSet<>();
            if (inferParameterContracts && !instructionsProtectedByNullPointerExceptionHandler.contains(instruction)) {
               if (parameterContext != null && parameterContext.remainingDepth > 0) {
                  final SourceValue[] arguments = determineArgumentValues(call, frames[instructionIndex]);
                  final Map<Integer, Integer> callerParametersByArgument = new HashMap<>();
                  // A requirement on a derived or ambiguous argument does not prove a requirement on an individual input.
                  for (int argumentIndex = 0; argumentIndex < arguments.length; argumentIndex++) {
                     final DependencySummary dependencies = determineDirectParameterDependencies(arguments[argumentIndex], frames,
                        instructionIndexes, referenceParameterIndexesByLocalSlot, parameterContext);
                     if (dependencies.proven && dependencies.parameterIndexes.size() == 1) {
                        callerParametersByArgument.put(argumentIndex, dependencies.parameterIndexes.iterator().next());
                     }
                  }
                  // Resolve a helper only when one of its arguments can establish an original caller-parameter fact.
                  if (!callerParametersByArgument.isEmpty()) {
                     final var childContext = new ParameterAnalysisContext(parameterContext.remainingDepth - 1, parameterContext.budget);
                     final MethodParameterAnalysis calledAnalysis = determineCalledMethodParameterAnalysis(call, childContext);
                     /* Keep independent local facts, but propagate traversal limits through every caller so a partial
                      * result cannot become reusable merely because the cycle, depth, or work cutoff occurred several calls below. */
                     parameterContext.cacheable &= childContext.cacheable;
                     // A helper accepting null does not prove that its caller accepts null, so only requirements transfer.
                     for (final int requiredArgument : calledAnalysis.definitelyNonNullParameterIndexes) {
                        final Integer callerParameter = callerParametersByArgument.get(requiredArgument);
                        if (callerParameter != null) {
                           // Argument and receiver evidence are independent; an instance call may establish both.
                           provenParameters.add(callerParameter);
                        }
                     }
                  }
               }
            } else if (!inferParameterContracts && call.getOpcode() == Opcodes.INVOKESTATIC && call.owner.equals("java/lang/System")
                  && call.name.equals("arraycopy") && call.desc.equals("(Ljava/lang/Object;ILjava/lang/Object;II)V")) {
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
            normalCompletionState = addDependencies(normalCompletionState, provenParameters);
         }

         final ParameterCheck check = inferParameterContracts ? branchChecks.get(instruction) : null;
         final Integer jumpTargetIndex = check == null ? null : instructionIndexes.get(((JumpInsnNode) instruction).label);

         for (final int successor : controlFlow.normalSuccessors.get(instructionIndex)) {
            Set<Integer> outgoingState = normalCompletionState;
            if (check != null && jumpTargetIndex != null && check.dependencies.proven && check.dependencies.parameterIndexes.size() == 1) {
               final boolean isNonNullEdge = check.predicate.edgeNullness(opcode, successor == jumpTargetIndex) == FlowNullness.NEVER_NULL;
               if (isNonNullEdge) {
                  /* Null guards and successful type tests prove a parameter non-null only on the corresponding edge.
                   * A failed instanceof test is not evidence that the parameter is null. */
                  outgoingState = addDependencies(normalCompletionState, check.dependencies.parameterIndexes);
               }
            }
            mergeGuaranteedNonNullParameters(states, reached, pendingInstructions, successor, outgoingState);
         }
         for (final int successor : controlFlow.exceptionSuccessors.get(instructionIndex)) {
            /* A call may throw before its receiver or arguments are checked. New facts belong only to normal completion;
             * handlers retain the incoming facts even when the helper rejects null with an exception other than NPE. */
            mergeGuaranteedNonNullParameters(states, reached, pendingInstructions, successor, incomingState);
         }
      }
      return states;
   }

   @SuppressWarnings("null")
   private MethodAnalysis analyzeMethod(final MethodNode method, final ReturnFlowFacts returnFlowFacts,
         final @Nullable ParameterAnalysisContext parameterContext) throws AnalyzerException {
      final AbstractInsnNode[] instructions = method.instructions.toArray();
      final var controlFlow = new ControlFlowAnalyzer(instructions, this::isProvenNonReturningCall);
      final Frame<SourceValue>[] frames = controlFlow.analyze(classNode.name, method);
      final Map<AbstractInsnNode, Integer> instructionIndexes = new IdentityHashMap<>();
      for (int i = 0; i < instructions.length; i++) {
         instructionIndexes.put(instructions[i], i);
      }
      final Map<Integer, Integer> parameterIndexes = determineReferenceParameterIndexesByLocalSlot(method);
      // Resolve fixed branch provenance once, with shared polarity and work accounting for every downstream consumer.
      final Map<AbstractInsnNode, ParameterCheck> branchChecks = determineBranchParameterChecks(method, instructions, frames, controlFlow,
         instructionIndexes, parameterContext);
      final List<Set<Integer>> guaranteedNullParameters = determineGuaranteedNullParameters(instructions, controlFlow, instructionIndexes,
         branchChecks);
      /* Parameter requirements and return dependencies use different evidence. In particular, delegated parameter
       * requirements must not silently broaden return inference, and the native arraycopy intrinsic remains return-only. */
      final List<Set<Integer>> guaranteedNonNullParameters = determineGuaranteedNonNullParameters(method, instructions, frames, controlFlow,
         instructionIndexes, branchChecks, parameterContext);
      final Set<Integer> definitelyNullableParameters = parameterContext != null ? determineDefinitelyNullableParameters(instructions,
         controlFlow, instructionIndexes, branchChecks) //
            : Set.of();
      return new MethodAnalysis(instructions, frames, instructionIndexes, parameterIndexes, new ParameterFlowFacts(guaranteedNullParameters,
         guaranteedNonNullParameters, definitelyNullableParameters), returnFlowFacts);
   }

   private DependencySummary determineMethodDependencySummary(final MethodNode method) {
      return determineMethodDependencySummary(method, null);
   }

   private DependencySummary determineMethodDependencySummary(final MethodNode method,
         final @Nullable ReturnFlowFacts availableReturnFlowFacts) {
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
               /* Direct return classification already computed these facts. Recursive helper summaries arrive here
                * without them and need one equivalent flow pass so their conditional non-null alternative is retained. */
               final ReturnFlowFacts returnFlowFacts = availableReturnFlowFacts == null ? determineReturnFlowFacts(method)
                     : availableReturnFlowFacts;
               result = determineMethodDependencySummary(analyzeMethod(method, returnFlowFacts, null));
               final Integer exactParameter = returnFlowFacts.exactReturnedParameterIndex;
               if (result.proven && !result.hasNonNullReturn && exactParameter != null && result.parameterIndexes.equals(Set.of(
                  exactParameter))) {
                  // Keep exact identity separate from ordinary null dependence; callers must not reverse a one-way contract.
                  result = new DependencySummary(true, false, result.parameterIndexes, exactParameter);
               }
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
      DependencySummary result = DependencySummary.EMPTY;
      boolean hasReferenceReturn = false;
      for (int i = 0; i < analysis.instructions.length; i++) {
         final Frame<SourceValue> frame = analysis.frames[i];
         if (!isReachableFrame(frame) || analysis.instructions[i].getOpcode() != Opcodes.ARETURN) {
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
            if (analysis.returnFlowFacts.provenNonNullLoads.contains(source))
               /* The value's original producer may be unknown, as with ConcurrentHashMap.getOrDefault's get() call.
                * At this exact load, however, the flow edge has already proven the current local non-null. */
               return DependencyExpansion.terminal(DependencySummary.NON_NULL);
            final SourceValue localValue = frame.getLocal(localSlot);
            if (!localValue.insns.isEmpty() && analysis.returnFlowFacts.loadsRetainingEntryParameter.contains(source))
               /* SourceInterpreter loses entry values when it unions assignment producers. A surviving entry value prevents
                * a replacement-only dependency, even when flow analysis pruned the assignment path. Untouched structural
                * loads have no assignment producers and retain their entry dependency below. */
               return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
            if (localSlot == 0 && parameterIndex == null && localValue.insns.isEmpty())
               /* In valid bytecode, a source-less reference in slot zero that is not a reference parameter can only be
                * the receiver; static locals require a producer. The JVM checks that receiver before method entry. */
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
         case Opcodes.INVOKEDYNAMIC:
            return DependencyExpansion.terminal(isKnownNonNullDynamicFactory((InvokeDynamicInsnNode) source) ? DependencySummary.NON_NULL
                  : DependencySummary.UNKNOWN);
         default:
            return DependencyExpansion.terminal(DependencySummary.UNKNOWN);
      }
   }

   @SuppressWarnings("null")
   private DependencyExpansion determineMethodCallExpansion(final MethodInsnNode call, final int instructionIndex,
         final MethodAnalysis callerAnalysis) {
      // These are deliberate call-contract heuristics; the dispatch restriction below applies only to body-derived summaries.
      if (isKnownNonNullMethod(call.getOpcode(), call.owner, call.name, call.desc) || resolvesToObjectGetClass(call))
         return DependencyExpansion.terminal(DependencySummary.NON_NULL);
      final Frame<SourceValue> frame = callerAnalysis.frames[instructionIndex];
      // Only completed flow analysis can authorize exact dispatch; missing flow facts leave this proof unavailable.
      final boolean receiverHasExactType = callerAnalysis.returnFlowFacts.exactReceiverCalls.contains(call);
      final DependencySummary calledMethodSummary = determineCalledMethodDependencySummary(call, receiverHasExactType);
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
      /* Forwarding the parameter sources alone would discard a separate non-null return alternative established in
       * the helper. Seed that fact explicitly while the caller-side provenance remaps the dependent alternatives. */
      return DependencyExpansion.forwarded(dependencies, calledMethodSummary.hasNonNullReturn ? DependencySummary.NON_NULL
            : DependencySummary.EMPTY);
   }

   @SuppressWarnings("null")
   private DependencySummary determineCalledMethodDependencySummary(final MethodInsnNode call, final boolean receiverHasExactType) {
      if (!hasExactSpecialTarget(call))
         // Check each caller before lookup: the resolver caches the named body, not its dispatch eligibility.
         return DependencySummary.UNKNOWN;
      if (call.owner.equals(classNode.name)) {
         final int opcode = call.getOpcode();
         final MethodNode calledMethod = findMethodNode(call.name, call.desc);
         if (calledMethod == null)
            return DependencySummary.UNKNOWN;
         final boolean ownerIsFinal = (classNode.access & Opcodes.ACC_FINAL) != 0;
         final boolean methodIsFinal = (calledMethod.access & Opcodes.ACC_FINAL) != 0;
         final boolean isOverridableVirtualCall = opcode == Opcodes.INVOKEVIRTUAL && !ownerIsFinal && !methodIsFinal;
         if (opcode == Opcodes.INVOKEINTERFACE || isOverridableVirtualCall && !receiverHasExactType)
            /* A classpath scan cannot prove that consumers will not add another subclass. The declared body is safe only
             * for statically bound calls, final dispatch, or an exact allocation at this call site; an override may
             * otherwise have a different null contract. */
            return DependencySummary.UNKNOWN;
         return determineMethodDependencySummary(calledMethod);
      }
      return methodSummaryResolver.determineExternalMethodSummary(call, receiverHasExactType);
   }

   @SuppressWarnings("null")
   private boolean resolvesToObjectGetClass(final MethodInsnNode call) {
      if (call.itf || call.getOpcode() != Opcodes.INVOKEVIRTUAL || !"getClass".equals(call.name) || !"()Ljava/lang/Class;".equals(
         call.desc))
         return false;
      if (call.owner.startsWith("["))
         // Arrays inherit Object's final method; successful invocation always returns their runtime Class.
         return true;
      final Set<String> visited = new HashSet<>();
      @Nullable
      String owner = call.owner;
      while (owner != null && visited.add(owner)) {
         if ("java/lang/Object".equals(owner))
            return true;
         final ClassNode ownerClass = owner.equals(classNode.name) ? classNode : methodSummaryResolver.resolveClass(owner);
         if (ownerClass == null || (ownerClass.access & Opcodes.ACC_INTERFACE) != 0 || findMethodNode(ownerClass, call.name,
            call.desc) != null)
            // Missing or inconsistent hierarchy evidence must not authorize a name-only intrinsic.
            return false;
         owner = ownerClass.superName;
      }
      return false;
   }

   @SuppressWarnings("null")
   private boolean isTransitivelyCloneable() {
      final var pendingTypes = new ArrayDeque<String>();
      final var visitedTypes = new HashSet<String>();
      pendingTypes.add(classNode.name);
      while (!pendingTypes.isEmpty()) {
         final String typeName = pendingTypes.removeFirst();
         if (!visitedTypes.add(typeName)) {
            continue;
         }
         if ("java/lang/Cloneable".equals(typeName))
            return true;

         final ClassNode type = typeName.equals(classNode.name) ? classNode : methodSummaryResolver.resolveClass(typeName);
         if (type == null) {
            // Missing hierarchy bytecode cannot establish that Object.clone() will accept every receiver.
            continue;
         }
         pendingTypes.addAll(type.interfaces);
         if (type.superName != null) {
            pendingTypes.add(type.superName);
         }
      }
      return false;
   }

   @SuppressWarnings("null")
   private boolean resolvesToObjectClone(final MethodInsnNode call) {
      // Interface-super calls select interface methods, so they cannot establish Object.clone's class-specific contract.
      if (call.itf || call.getOpcode() != Opcodes.INVOKESPECIAL || !call.name.equals("clone") || !call.desc.equals("()Ljava/lang/Object;"))
         return false;

      final var visitedTypes = new HashSet<String>();
      /* For a superclass reference, JVM selection starts at the immediate parent even when the instruction names Object.
       * Unlike exact-declaration summaries, this walk can retain inherited Object.clone proofs when no declaration intervenes. */
      @Nullable
      String owner = call.owner.equals(classNode.name) ? classNode.name : classNode.superName;
      while (owner != null && visitedTypes.add(owner)) {
         if ("java/lang/Object".equals(owner))
            return true;

         final ClassNode ownerClass = owner.equals(classNode.name) ? classNode : methodSummaryResolver.resolveClass(owner);
         if (ownerClass == null)
            return false;
         if (findMethodNode(ownerClass, call.name, call.desc) != null)
            // An intervening clone declaration can return null or throw despite the receiver being Cloneable.
            return false;
         owner = ownerClass.superName;
      }
      return false;
   }

   private Set<TryCatchBlockNode> determineImpossibleCloneExceptionHandlers(final MethodNode methodNode) {
      final List<TryCatchBlockNode> cloneExceptionHandlers = new ArrayList<>();
      for (final TryCatchBlockNode tryCatchBlock : methodNode.tryCatchBlocks) {
         if ("java/lang/CloneNotSupportedException".equals(tryCatchBlock.type)) {
            cloneExceptionHandlers.add(tryCatchBlock);
         }
      }
      if (cloneExceptionHandlers.isEmpty() || !isTransitivelyCloneable())
         return Set.of();

      final Set<TryCatchBlockNode> result = Collections.newSetFromMap(new IdentityHashMap<>());
      for (final TryCatchBlockNode tryCatchBlock : cloneExceptionHandlers) {
         boolean hasObjectCloneCall = false;
         boolean hasOtherExceptionSource = false;
         /* Another invocation or an explicit throw can genuinely deliver CloneNotSupportedException to this handler,
          * so the exact Object.clone() call must be the protected region's only possible checked-exception source. */
         for (AbstractInsnNode instruction = tryCatchBlock.start; instruction != null
               && instruction != tryCatchBlock.end; instruction = instruction.getNext()) {
            if (instruction instanceof MethodInsnNode) {
               if (!resolvesToObjectClone((MethodInsnNode) instruction)) {
                  hasOtherExceptionSource = true;
                  break;
               }
               hasObjectCloneCall = true;
            } else if (instruction instanceof InvokeDynamicInsnNode || instruction.getOpcode() == Opcodes.ATHROW) {
               hasOtherExceptionSource = true;
               break;
            }
         }
         if (hasObjectCloneCall && !hasOtherExceptionSource) {
            /* invokespecial's protected-receiver rule restricts Object.clone() to this class or a subclass. Because the
             * current class is transitively Cloneable, that exact call cannot enter this checked handler. */
            result.add(tryCatchBlock);
         }
      }
      return result;
   }

   private ReturnFlowFacts determineReturnFlowFacts(final MethodNode methodNode) {
      return determineReturnFlowFacts(methodNode, true);
   }

   /* ASM stores null frames for unreachable instructions despite exposing an unannotated array. ECJ therefore needs
    * both suppressions for the defensive null-frame branch below. */
   @SuppressWarnings({"null", "unused"})
   private ReturnFlowFacts determineReturnFlowFacts(final MethodNode methodNode, final boolean forwardNullArguments) {
      final AbstractInsnNode[] instructions = methodNode.instructions.toArray();
      /* Unknown parameters, fields, and calls can invalidate a path-insensitive non-null result without introducing
       * ACONST_NULL. Inspect every reachable reference return rather than using null constants as an entry condition. */
      try {
         final Set<TryCatchBlockNode> ignoredExceptionHandlers = determineImpossibleCloneExceptionHandlers(methodNode);
         Frame<SourceValue>[] frames = new FlowAnalyzer(new FlowInterpreter(true, forwardNullArguments), Map.of(), ignoredExceptionHandlers,
            this::isProvenNonReturningCall).analyze(classNode.name, methodNode);
         final Map<AbstractInsnNode, String> testedPrivateFields = new IdentityHashMap<>();
         for (int i = 0; i < instructions.length; i++) {
            final int opcode = instructions[i].getOpcode();
            final Frame<SourceValue> frame = frames[i];
            if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && isReachableFrame(frame) && frame.getStackSize() > 0) {
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
         final var fieldFactBudget = new AnalysisWorkBudget(MAX_FIELD_FACT_ANALYSIS_WORK);
         if (!testedPrivateFields.isEmpty()) {
            /* Compute field facts separately from ASM's widening value lattice. Calls and writes kill these must-facts,
             * so a local holding an earlier field value cannot make a later field read look non-null. */
            final Map<AbstractInsnNode, Set<String>> knownFieldsAtInstruction = determineKnownNonNullFieldsAtInstructions(methodNode,
               instructions, Map.of(), testedPrivateFields, fieldFactBudget);
            frames = new FlowAnalyzer(new FlowInterpreter(true, forwardNullArguments), knownFieldsAtInstruction, ignoredExceptionHandlers,
               this::isProvenNonReturningCall).analyze(classNode.name, methodNode);
         }

         final Map<AbstractInsnNode, Set<String>> candidateBranchRequirements = new IdentityHashMap<>();
         final Map<AbstractInsnNode, FlowValue> testedBranchValues = new IdentityHashMap<>();
         for (int i = 0; i < instructions.length; i++) {
            final int opcode = instructions[i].getOpcode();
            final Frame<SourceValue> frame = frames[i];
            if ((opcode == Opcodes.IFNULL || opcode == Opcodes.IFNONNULL) && isReachableFrame(frame) && frame.getStackSize() > 0) {
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
            frames = new FlowAnalyzer(new FlowInterpreter(true, forwardNullArguments), knownFieldsAtInstruction, ignoredExceptionHandlers,
               this::isProvenNonReturningCall).analyze(classNode.name, methodNode);
         }
         boolean hasReachableReturn = false;
         boolean hasProvenNullReturn = false;
         boolean allReturnsAreNonNull = true;
         boolean allReturnsForwardSameParameter = true;
         @Nullable
         Integer exactReturnedParameterIndex = null;
         // Provenance uses entry local slots; returned dependencies use descriptor indexes, excluding this and wide-slot padding.
         final Map<Integer, Integer> referenceParameterIndexes = determineReferenceParameterIndexesByLocalSlot(methodNode);
         final Set<AbstractInsnNode> provenNonNullLoads = Collections.newSetFromMap(new IdentityHashMap<>());
         final Set<AbstractInsnNode> loadsRetainingEntryParameter = Collections.newSetFromMap(new IdentityHashMap<>());
         final Set<AbstractInsnNode> exactReceiverCalls = Collections.newSetFromMap(new IdentityHashMap<>());
         for (int i = 0; i < instructions.length; i++) {
            final Frame<SourceValue> frame = frames[i];
            if (frame == null) {
               // ASM represents an unreachable instruction with a null array entry despite the generic array annotation.
               continue;
            }
            if (!(frame instanceof FlowFrame))
               return ReturnFlowFacts.UNKNOWN;
            if (!((FlowFrame) frame).reachable) {
               continue;
            }

            final int opcode = instructions[i].getOpcode();
            if (opcode == Opcodes.ALOAD) {
               final int localSlot = ((VarInsnNode) instructions[i]).var;
               if (localSlot < frame.getLocals()) {
                  final SourceValue localValue = frame.getLocal(localSlot);
                  if (localValue instanceof FlowValue) {
                     final FlowValue flowValue = (FlowValue) localValue;
                     if (flowValue.nullness == FlowNullness.NEVER_NULL) {
                        provenNonNullLoads.add(instructions[i]);
                     } else if (referenceParameterIndexes.containsKey(localSlot) && flowValue.parameterLocalSlots.contains(localSlot)) {
                        /* Only entry-parameter slots can retain a producer-less value beside an assignment. An ordinary
                         * local has an initialization producer, so its merged alternatives remain traceable. Keep untouched
                         * entry loads too: structural analysis can retain assignments from branches pruned by this flow pass. */
                        loadsRetainingEntryParameter.add(instructions[i]);
                     }
                  }
               }
            }
            if (opcode == Opcodes.INVOKEVIRTUAL) {
               final MethodInsnNode call = (MethodInsnNode) instructions[i];
               final SourceValue receiver = determineReceiverValue(call, frame);
               if (receiver != null && isExactAllocationValue(receiver, call.owner)) {
                  exactReceiverCalls.add(call);
               }
            }
            if (opcode != Opcodes.ARETURN) {
               continue;
            }
            hasReachableReturn = true;
            if (frame.getStackSize() == 0)
               return ReturnFlowFacts.UNKNOWN;

            final SourceValue returnedValue = frame.getStack(frame.getStackSize() - 1);
            if (!(returnedValue instanceof FlowValue))
               return ReturnFlowFacts.UNKNOWN;
            final FlowValue returnedFlowValue = (FlowValue) returnedValue;
            if (allReturnsForwardSameParameter) {
               /* Entry parameters have no producer instruction; copies and casts preserve that identity. Both checks
                * matter because merging with a produced value retains the original parameter in the provenance union. */
               final Integer parameterIndex = returnedFlowValue.insns.isEmpty() && returnedFlowValue.parameterLocalSlots.size() == 1
                     ? referenceParameterIndexes.get(returnedFlowValue.parameterLocalSlots.iterator().next())
                     : null;
               if (parameterIndex == null || exactReturnedParameterIndex != null && !exactReturnedParameterIndex.equals(parameterIndex)) {
                  // A mismatching return permanently defeats the proof; later matching returns cannot restore it.
                  allReturnsForwardSameParameter = false;
               } else {
                  exactReturnedParameterIndex = parameterIndex;
               }
            }
            final FlowNullness returnNullness = returnedFlowValue.nullness;
            if (returnNullness == FlowNullness.DEFINITELY_NULL || returnNullness == FlowNullness.MAY_INCLUDE_NULL) {
               /* MAY_INCLUDE_NULL is not ordinary uncertainty: its merge invariant requires at least one proven-null
                * producer. This also recognizes a parameter returned from an edge on which IFNULL proved it null. */
               hasProvenNullReturn = true;
            }
            if (returnNullness != FlowNullness.NEVER_NULL) {
               allReturnsAreNonNull = false;
            }
         }
         final ReturnEvidence evidence = hasProvenNullReturn ? ReturnEvidence.PROVEN_NULL
               : hasReachableReturn && allReturnsAreNonNull ? ReturnEvidence.PROVEN_NON_NULL : ReturnEvidence.UNKNOWN;
         // No normal return supplies no identity evidence, even though neither all-returns check encountered a counterexample.
         return new ReturnFlowFacts(evidence, provenNonNullLoads, loadsRetainingEntryParameter, exactReceiverCalls, hasReachableReturn
               && allReturnsForwardSameParameter ? exactReturnedParameterIndex : null);
      } catch (final AnalyzerException ex) {
         // ASM wraps interpreter failures. Preserve the depth signal so partial summaries cannot enter reusable caches.
         for (Throwable cause = ex.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof MethodSummaryDepthExceededException)
               throw (MethodSummaryDepthExceededException) cause;
         }
         // Flow analysis contributes only positive evidence; unsupported bytecode must remain unknown.
         System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING, "Failed to analyze control flow of "
               + classNode.name + "." + methodNode.name + methodNode.desc, ex);
         return ReturnFlowFacts.UNKNOWN;
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

   private static boolean isReachableFrame(final @Nullable Frame<SourceValue> frame) {
      /* ASM still allocates a successor frame after its control-flow callback. FlowFrame carries the stronger reachability
       * state needed to distinguish that dead normal continuation from a live exception-handler entry. */
      return frame != null && (!(frame instanceof FlowFrame) || ((FlowFrame) frame).reachable);
   }

   /**
    * Returns parameter contracts proven by local bytecode and non-null requirements of exactly resolved helpers.
    */
   @SuppressWarnings("null") // ClassGraph supplies a descriptor for every method but does not annotate that API contract.
   public MethodParameterAnalysis determineMethodParameterAnalysis(final MethodInfo methodInfo) {
      final String methodName = methodInfo.getName();
      final @NonNull String methodDescriptor = methodInfo.getTypeDescriptorStr();
      final MethodNode methodNode = findMethodNode(methodName, methodDescriptor);
      if (methodNode == null)
         return MethodParameterAnalysis.EMPTY;
      final var budget = new AnalysisWorkBudget(MAX_PARAMETER_ANALYSIS_WORK);
      final var result = methodSummaryResolver.determineParameterSummary(classNode, methodNode, new ParameterAnalysisContext(
         MAX_METHOD_SUMMARY_DEPTH, budget));
      if (budget.exceeded) {
         // Report once per root, rather than once for every sibling skipped after the common allowance is exhausted.
         System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING,
            "Skipping optional delegated parameter evidence of {0}.{1}{2}: cumulative analysis work exceeded {3}", classNode.name,
            methodName, methodDescriptor, MAX_PARAMETER_ANALYSIS_WORK);
      }
      return result;
   }

   @SuppressWarnings("null")
   private MethodParameterAnalysis determineCalledMethodParameterAnalysis(final MethodInsnNode call,
         final ParameterAnalysisContext context) {
      final ClassNode owner = call.owner.equals(classNode.name) ? classNode : methodSummaryResolver.resolveClass(call.owner);
      if (owner == null)
         return MethodParameterAnalysis.EMPTY;
      final MethodNode method = findMethodNode(owner, call.name, call.desc);
      if (method == null)
         // An inherited symbolic owner requires full JVM method resolution; exact declarations suffice for this proof.
         return MethodParameterAnalysis.EMPTY;

      if (!hasExactDeclaredTarget(call, owner, method))
         return MethodParameterAnalysis.EMPTY;

      // Eligibility is a call-site property and must be checked even when the declared body's summary is already cached.
      return methodSummaryResolver.determineParameterSummary(owner, method, context);
   }

   @SuppressWarnings({"null", "unused"})
   private MethodParameterAnalysis determineMethodParameterAnalysis(final MethodNode methodNode, final ParameterAnalysisContext context) {
      if ((methodNode.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) != 0 || determineReferenceParameterIndexesByLocalSlot(
         methodNode).isEmpty())
         return MethodParameterAnalysis.EMPTY;
      if (!isWithinAnalysisBudget(methodNode)) {
         logAnalysisBudgetExceeded(classNode.name, methodNode);
         return MethodParameterAnalysis.EMPTY;
      }

      try {
         final MethodAnalysis analysis = analyzeMethod(methodNode, ReturnFlowFacts.UNKNOWN, context);
         @Nullable
         Set<Integer> definitelyNonNullParameters = null;
         for (int i = 0; i < analysis.instructions.length; i++) {
            if (!isReachableFrame(analysis.frames[i]) || !isNormalReturnInstruction(analysis.instructions[i].getOpcode())) {
               continue;
            }

            if (definitelyNonNullParameters == null) {
               definitelyNonNullParameters = new HashSet<>(analysis.guaranteedNonNullParameters.get(i));
            } else {
               // The public contract is a must-fact across complete normal executions, not merely one successful branch.
               definitelyNonNullParameters.retainAll(analysis.guaranteedNonNullParameters.get(i));
            }
         }

         // An always-throwing method supplies no successful execution from which to infer a caller-facing contract.
         if (definitelyNonNullParameters == null)
            return MethodParameterAnalysis.EMPTY;

         final Set<Integer> definitelyNullableParameters = new HashSet<>(analysis.definitelyNullableParameters);
         final Set<Integer> conflictingParameters = new HashSet<>(definitelyNullableParameters);
         conflictingParameters.retainAll(definitelyNonNullParameters);
         /* The proofs should be disjoint. If conservative approximations ever disagree, silence is safer than emitting
          * either caller-facing contract and makes the anomaly local to the affected parameter. */
         definitelyNullableParameters.removeAll(conflictingParameters);
         definitelyNonNullParameters.removeAll(conflictingParameters);
         return new MethodParameterAnalysis(definitelyNullableParameters, definitelyNonNullParameters);
      } catch (final AnalyzerException ex) {
         // Parameter inference contributes only positive evidence; unsupported bytecode leaves every parameter unknown.
         System.getLogger(BytecodeAnalyzer.class.getName()).log(System.Logger.Level.WARNING, "Failed to analyze parameter nullness of "
               + classNode.name + "." + methodNode.name + methodNode.desc, ex);
      }
      return MethodParameterAnalysis.EMPTY;
   }

   /**
    * Returns the zero-based descriptor indexes of reference parameters proven non-null on every normal return, including
    * requirements propagated through exactly resolved helpers.
    */
   public Set<Integer> determineDefinitelyNonNullMethodParameters(final MethodInfo methodInfo) {
      return determineMethodParameterAnalysis(methodInfo).getDefinitelyNonNullParameterIndexes();
   }

   /**
    * Returns the zero-based descriptor indexes of reference parameters that a direct entry guard accepts as null.
    */
   public Set<Integer> determineDefinitelyNullableMethodParameters(final MethodInfo methodInfo) {
      return determineMethodParameterAnalysis(methodInfo).getDefinitelyNullableParameterIndexes();
   }

   /**
    * Analyzes bytecode to determine the nullability of method return types.
    */
   public Nullability determineMethodReturnTypeNullability(final MethodInfo methodInfo) {
      return determineMethodReturnAnalysis(methodInfo).getNullability();
   }

   /**
    * Analyzes bytecode to determine return nullability and the exact parameter dependencies of a PolyNull result.
    */
   public MethodReturnAnalysis determineMethodReturnAnalysis(final MethodInfo methodInfo) {
      switch (ClassGraphUtils.getMethodReturnKind(methodInfo)) {
         case PRIMITIVE:
         case VOID:
            return new MethodReturnAnalysis(Nullability.NEVER_NULL, Set.of());
         default:
            // continue analysis for object return types
      }

      if (methodInfo.isAbstract())
         return new MethodReturnAnalysis(Nullability.UNKNOWN, Set.of());

      final String methodName = methodInfo.getName();
      @SuppressWarnings("null")
      final @NonNull String methodDescriptor = methodInfo.getTypeDescriptorStr();
      final MethodNode methodNode = findMethodNode(methodName, methodDescriptor);
      if (methodNode == null)
         return new MethodReturnAnalysis(Nullability.UNKNOWN, Set.of());
      if (!isWithinAnalysisBudget(methodNode)) {
         logAnalysisBudgetExceeded(classNode.name, methodNode);
         return new MethodReturnAnalysis(Nullability.UNKNOWN, Set.of());
      }

      ReturnFlowFacts returnFlowFacts;
      try {
         returnFlowFacts = determineReturnFlowFacts(methodNode);
      } catch (final MethodSummaryDepthExceededException ex) {
         // Keep independent local evidence when optional null forwarding reaches the shared method-summary depth limit.
         returnFlowFacts = determineReturnFlowFacts(methodNode, false);
      }
      final ReturnEvidence returnEvidence = returnFlowFacts.evidence;
      if (returnEvidence == ReturnEvidence.PROVEN_NON_NULL)
         // Returning an argument after rejecting null has a stronger contract than forwarding that argument alone.
         return new MethodReturnAnalysis(Nullability.NEVER_NULL, Set.of());

      DependencySummary dependencySummary;
      try {
         dependencySummary = determineMethodDependencySummary(methodNode, returnFlowFacts);
      } catch (final MethodSummaryDepthExceededException ex) {
         // Exhausting recursive summaries must not discard independent local null or exact-forwarding evidence.
         dependencySummary = DependencySummary.UNKNOWN;
      }

      if (returnEvidence == ReturnEvidence.UNKNOWN && dependencySummary.proven && dependencySummary.hasNonNullReturn
            && dependencySummary.parameterIndexes.isEmpty())
         /* A call contract such as System.arraycopy can prove a forwarded argument non-null on normal completion.
          * Preserve that stronger summary, while flow-proven null returns still override contradictory summary evidence. */
         return new MethodReturnAnalysis(Nullability.NEVER_NULL, Set.of());

      if (returnFlowFacts.exactReturnedParameterIndex != null)
         // Exact forwarding proves the dependency without a separate null-return branch or a successful recursive summary.
         return new MethodReturnAnalysis(Nullability.POLY_NULL, Set.of(returnFlowFacts.exactReturnedParameterIndex));

      if (returnEvidence == ReturnEvidence.PROVEN_NULL) {
         if (dependencySummary.proven && !dependencySummary.parameterIndexes.isEmpty())
            /* A conditional dependency does not prove exact forwarding. Without that stronger proof, require independent
             * flow evidence that a null value reaches ARETURN before interpreting the dependency as PolyNull. */
            return new MethodReturnAnalysis(Nullability.POLY_NULL, dependencySummary.parameterIndexes);
         return new MethodReturnAnalysis(Nullability.DEFINITELY_NULL, Set.of());
      }

      if (dependencySummary.proven && !dependencySummary.parameterIndexes.isEmpty() && (dependencySummary.hasNonNullReturn
            || dependencySummary.parameterIndexes.size() == 1))
         /* A complete single-parameter proof can establish forwarding through helpers without a local null branch.
          * Preserve the existing boundary for competing parameters: those need null-return evidence or a non-null alternative. */
         return new MethodReturnAnalysis(Nullability.POLY_NULL, dependencySummary.parameterIndexes);

      return new MethodReturnAnalysis(Nullability.UNKNOWN, Set.of());
   }
}
