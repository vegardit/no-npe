/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.eclipse.jdt.annotation.Nullable;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.util.Textifier;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;

/**
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class BytecodeAnalyzer {

   private static final boolean DEBUG = false;

   public enum Nullability {
      /** a method never returns null */
      NEVER_NULL,

      /** at least one code branch definitely returns null */
      DEFINITLY_NULL,

      /** a method may or may not return null */
      UNKNOWN,

      /** a method only returns null when an argument is null */
      POLY_NULL
   }

   static class Instruction {
      final int opcode;

      Instruction(final int opcode) {
         this.opcode = opcode;
      }

      @Override
      public int hashCode() {
         return Objects.hash(opcode);
      }

      @Override
      public boolean equals(final @Nullable Object obj) {
         if (this == obj)
            return true;
         if (obj == null || getClass() != obj.getClass())
            return false;
         final Instruction other = (Instruction) obj;
         return opcode == other.opcode;
      }

      @Override
      @SuppressWarnings("null")
      public String toString() {
         return Textifier.OPCODES[opcode];
      }
   }

   static final class VarInstruction extends Instruction {
      final int varIndex;

      VarInstruction(final int opcode, final int varIndex) {
         super(opcode);
         this.varIndex = varIndex;
      }

      @Override
      public int hashCode() {
         final int prime = 31;
         int result = super.hashCode();
         result = prime * result + Objects.hash(varIndex);
         return result;
      }

      @Override
      public boolean equals(final @Nullable Object obj) {
         if (this == obj)
            return true;
         if (obj == null || getClass() != obj.getClass())
            return false;
         final VarInstruction other = (VarInstruction) obj;
         return opcode == other.opcode && varIndex == other.varIndex;
      }

      @Override
      public String toString() {
         return Textifier.OPCODES[opcode] + " " + varIndex;
      }
   }

   private final ClassReader classReader;

   public BytecodeAnalyzer(final ClassInfo classInfo) {
      try (var classFileResource = classInfo.getResource()) {
         if (classFileResource == null)
            throw new IOException("Class resource not found: " + classInfo);
         try (var is = classFileResource.open()) {
            classReader = new ClassReader(is);
         }
      } catch (final IOException ex) {
         throw new UncheckedIOException("Failed to read class resource: " + classInfo, ex);
      }
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
      final String methodDescriptor = methodInfo.getTypeDescriptorStr();
      final int methodArgumentCount = methodInfo.getParameterInfo().length;

      final var returnNullabilities = new ArrayList<Nullability>();

      if (DEBUG) {
         System.out.println("===========================");
         System.out.println(methodName + methodDescriptor);
      }
      classReader.accept(new ClassVisitor(Opcodes.ASM9) {
         @Override
         @NonNullByDefault({})
         public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature,
               final String[] exceptions) {
            if (!name.equals(methodName) || !descriptor.equals(methodDescriptor))
               return super.visitMethod(access, name, descriptor, signature, exceptions);

            return new MethodVisitor(Opcodes.ASM9) {
               /** tracks the nullability of operands on the stack */
               private final Deque<Nullability> operandStackNullability = new ArrayDeque<>();
               private final Map<Integer, Nullability> localVariableNullability = new HashMap<>();
               private final List<Instruction> instructions = new ArrayList<>();

               private void debugLogVisit(final String msg) {
                  if (DEBUG) {
                     System.out.println(" | " + operandStackNullability);
                     System.out.println(" > " + msg);
                  }
               }

               private boolean isKnownNonNullMethod(final String clazz, final String methodName, final String descriptor) {
                  // CHECKSTYLE:IGNORE .* FOR NEXT 6 LINES
                  return methodName.equals("<init>") //
                        || methodName.equals("toString") && descriptor.equals("()Ljava/lang/String;") //
                        || clazz.equals("java/lang/String") && methodName.equals("valueOf") && descriptor.equals(
                           "(Ljava/lang/Object;)Ljava/lang/String;") //
                        || clazz.equals("java/lang/StringBuilder") && (methodName.startsWith("append") || methodName.startsWith("insert")) //
                        || clazz.equals("java/lang/invoke/StringConcatFactory") && methodName.startsWith("makeConcat");
               }

               private boolean isMethodArgument(final int varIndex) {
                  final int firstLocalVariableIndex = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                  final int argIndex = varIndex - firstLocalVariableIndex;
                  return argIndex >= 0 && argIndex < methodArgumentCount;
               }

               @Override
               public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
                  debugLogVisit("visitFieldInsn " + Textifier.OPCODES[opcode]);
                  instructions.add(new Instruction(opcode));
                  switch (opcode) {
                     case Opcodes.GETSTATIC:
                     case Opcodes.GETFIELD:
                        // pushing a field value onto the stack; nullability unknown
                        operandStackNullability.push(Nullability.UNKNOWN);
                        break;
                     case Opcodes.PUTSTATIC:
                     case Opcodes.PUTFIELD:
                        if (!operandStackNullability.isEmpty()) {
                           operandStackNullability.pop();
                        }
                        break;
                     default:
                        operandStackNullability.push(Nullability.UNKNOWN);
                        break;
                  }
               }

               @Override
               public void visitJumpInsn(final int opcode, final Label label) {
                  debugLogVisit("visitJumpInsn " + Textifier.OPCODES[opcode] + " " + label);
                  instructions.add(new Instruction(opcode));
               }

               @Override
               public void visitInsn(final int opcode) {
                  debugLogVisit("visitInsn " + Textifier.OPCODES[opcode]);
                  instructions.add(new Instruction(opcode));
                  switch (opcode) {
                     case Opcodes.ACONST_NULL:
                        operandStackNullability.push(Nullability.DEFINITLY_NULL);
                        break;
                     case Opcodes.ARETURN:
                        if (operandStackNullability.isEmpty()) {
                           // stack underflow; treat as possibly null
                           returnNullabilities.add(Nullability.UNKNOWN);
                        } else {

                           /* handle:
                            * if (arg1 == null)
                            *     return null;
                            *
                            * which translates to:
                            *   [ALOAD 0, IFNONNULL, ACONST_NULL, ARETURN]
                            *
                            * or:
                            * if (arg1 == null)
                            *     return arg1;
                            *
                            * which translates to:
                            *   [ALOAD 0, IFNONNULL, ALOAD 0, ARETURN]
                            */
                           final var count = instructions.size();
                           if (count > 3 //
                                 && instructions.get(count - 3).opcode == Opcodes.IFNONNULL //
                                 && instructions.get(count - 4).opcode == Opcodes.ALOAD //
                                 && isMethodArgument(((VarInstruction) instructions.get(count - 4)).varIndex) //
                                 && (instructions.get(count - 2).opcode == Opcodes.ACONST_NULL //
                                       || instructions.get(count - 2).equals(instructions.get(count - 4)))) {
                              returnNullabilities.add(Nullability.POLY_NULL);
                           } else {
                              final Nullability returnValue = operandStackNullability.pop();
                              returnNullabilities.add(returnValue);
                           }
                        }
                        // clear the operand stack after a return
                        operandStackNullability.clear();
                        instructions.clear();
                        break;
                     case Opcodes.DUP:
                        if (operandStackNullability.isEmpty()) {
                           // stack underflow; treat as possibly null
                           operandStackNullability.push(Nullability.UNKNOWN);
                        } else {
                           final Nullability top = operandStackNullability.peek();
                           operandStackNullability.push(top);
                        }
                        break;
                     case Opcodes.POP:
                        if (!operandStackNullability.isEmpty()) {
                           operandStackNullability.pop();
                        }
                        break;
                     default:
                        // for other instructions, assume they might alter the stack
                        // for simplicity, we reset the stack to UNKNOWN
                        operandStackNullability.clear();
                        operandStackNullability.push(Nullability.UNKNOWN);
                        break;
                  }
               }

               @Override
               public void visitLdcInsn(final Object constant) {
                  debugLogVisit("visitLdcInsn " + constant + " " + constant.getClass());
                  instructions.add(new Instruction(Opcodes.LDC));
                  if (constant instanceof Integer || constant instanceof Float //
                        || constant instanceof Long || constant instanceof Double //
                        || constant instanceof String //
                        || constant instanceof Type || constant instanceof Handle) {
                     // primitive constants, string constants, class literals, method handles are non-null
                     operandStackNullability.push(Nullability.NEVER_NULL);
                  } else if (constant instanceof ConstantDynamic) {
                     // ConstantDynamic may resolve to null, so treat it as possibly null
                     operandStackNullability.push(Nullability.UNKNOWN);
                  } else {
                     // handle other unexpected types conservatively as possibly null
                     operandStackNullability.push(Nullability.UNKNOWN);
                  }
               }

               @Override
               public void visitIntInsn(final int opcode, final int operand) {
                  debugLogVisit("visitIntInsn " + Textifier.OPCODES[opcode] + " " + operand);
                  instructions.add(new Instruction(opcode));
                  if (opcode == Opcodes.NEWARRAY) {
                     operandStackNullability.push(Nullability.NEVER_NULL);
                  }
               }

               @Override
               public void visitInvokeDynamicInsn(final String name, final String descriptor, final Handle bootstrapMethodHandle,
                     final Object... bootstrapMethodArgs) {
                  visitMethodInsn(Opcodes.INVOKEDYNAMIC, bootstrapMethodHandle.getOwner(), name, descriptor, bootstrapMethodHandle
                     .isInterface());
               }

               @Override
               public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor,
                     final boolean isInterface) {
                  debugLogVisit("visitMethodInsn " + Textifier.OPCODES[opcode] + " " + owner + "." + name + descriptor);
                  instructions.add(new Instruction(opcode));

                  // pop arguments off the stack
                  final Type methodType = Type.getMethodType(descriptor);
                  for (int i = 0, argCount = methodType.getArgumentTypes().length; i < argCount; i++) {
                     if (!operandStackNullability.isEmpty()) {
                        operandStackNullability.pop();
                     }
                  }

                  if (opcode != Opcodes.INVOKESTATIC) {
                     // pop 'this' reference
                     if (!operandStackNullability.isEmpty()) {
                        operandStackNullability.pop();
                     }
                  }

                  // push the return value onto the stack
                  switch (methodType.getReturnType().getSort()) {
                     case Type.VOID:
                        break;
                     case Type.OBJECT:
                     case Type.ARRAY:
                        if (isKnownNonNullMethod(owner, name, descriptor)) {
                           operandStackNullability.push(Nullability.NEVER_NULL);
                        } else {
                           // reference type; nullability unknown
                           operandStackNullability.push(Nullability.UNKNOWN);
                        }
                        break;
                     default:
                        // primitive type; definitely non-null
                        operandStackNullability.push(Nullability.NEVER_NULL);
                  }
               }

               @Override
               public void visitTypeInsn(final int opcode, final String type) {
                  debugLogVisit("visitTypeInsn " + Textifier.OPCODES[opcode] + " " + type);
                  instructions.add(new Instruction(opcode));

                  switch (opcode) {
                     case Opcodes.NEW:
                     case Opcodes.ANEWARRAY:
                        operandStackNullability.push(Nullability.NEVER_NULL);
                        break;
                     case Opcodes.CHECKCAST:
                     case Opcodes.INSTANCEOF:
                        break;
                     default:
                        operandStackNullability.push(Nullability.UNKNOWN);
                  }
               }

               @Override
               public void visitVarInsn(final int opcode, final int varIndex) {
                  debugLogVisit("visitVarInsn " + Textifier.OPCODES[opcode] + " " + varIndex);
                  instructions.add(new VarInstruction(opcode, varIndex));

                  switch (opcode) {
                     case Opcodes.ALOAD:
                        // loading a reference from a local variable
                        final Nullability varNullability = localVariableNullability.getOrDefault(varIndex, Nullability.UNKNOWN);
                        operandStackNullability.push(varNullability);
                        break;
                     case Opcodes.ASTORE:
                        // storing a value into a local variable
                        if (!operandStackNullability.isEmpty()) { // CHECKSTYLE:IGNORE .*
                           final Nullability valueNullability = operandStackNullability.pop();
                           localVariableNullability.put(varIndex, valueNullability);
                        } else {
                           // stack underflow; assume possibly null
                           localVariableNullability.put(varIndex, Nullability.UNKNOWN);
                        }
                        break;
                     default:
                        operandStackNullability.push(Nullability.UNKNOWN);
                        break;
                  }
               }
            };
         }
      }, 0);

      if (returnNullabilities.isEmpty())
         // no return statements returning objects found; nullability unknown
         return Nullability.NEVER_NULL;

      if (returnNullabilities.contains(Nullability.DEFINITLY_NULL))
         return Nullability.DEFINITLY_NULL;

      if (returnNullabilities.contains(Nullability.UNKNOWN))
         return Nullability.UNKNOWN;

      if (returnNullabilities.contains(Nullability.POLY_NULL))
         return Nullability.POLY_NULL;

      return Nullability.NEVER_NULL;
   }
}
