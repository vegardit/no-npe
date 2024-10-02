/*
 * SPDX-FileCopyrightText: © Vegard IT GmbH (https://vegardit.com) and contributors.
 * SPDX-License-Identifier: EPL-2.0
 */
package com.vegardit.no_npe.eea_generator.internal;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.jdt.annotation.NonNullByDefault;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import io.github.classgraph.ClassInfo;
import io.github.classgraph.MethodInfo;

/**
 * Analyzes bytecode to determine the nullability of method return types.
 *
 * @author Sebastian Thomschke (https://sebthom.de), Vegard IT GmbH (https://vegardit.com)
 */
public class BytecodeAnalyzer {

   public enum Nullability {
      NON_NULL,
      NULLABLE,
      UNKNOWN;

      public boolean isNullable() {
         return this == NULLABLE;
      }

      public boolean isNonNull() {
         return this == NON_NULL;
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

   public Nullability determineMethodReturnTypeNullability(final MethodInfo methodInfo) {
      switch (ClassGraphUtils.getMethodReturnKind(methodInfo)) {
         case PRIMITIVE:
         case VOID:
            return Nullability.NON_NULL;
         default:
      }

      if (methodInfo.isAbstract())
         return Nullability.UNKNOWN;

      final String methodName = methodInfo.getName();
      final String methodDescriptor = methodInfo.getTypeDescriptorStr();

      final var returnNullabilities = new ArrayList<Nullability>();
      classReader.accept(new ClassVisitor(Opcodes.ASM9) {
         @Override
         @NonNullByDefault({})
         public MethodVisitor visitMethod(final int access, final String name, final String descriptor, final String signature,
               final String[] exceptions) {
            if (name.equals(methodName) && descriptor.equals(methodDescriptor))
               return new MethodVisitor(Opcodes.ASM9) {
                  private final ArrayDeque<Nullability> operandStack = new ArrayDeque<>();
                  private final Map<Integer, Nullability> localVariableNullability = new HashMap<>();

                  private boolean isKnownNonNullMethod(final String clazz, final String methodName, final String descriptor) {
                     // CHECKSTYLE:IGNORE .* FOR NEXT 5 LINES
                     return methodName.equals("<init>") //
                           || methodName.equals("toString") && descriptor.equals("()Ljava/lang/String;") //
                           || clazz.equals("java/lang/String") && methodName.equals("valueOf") && descriptor.equals(
                              "(Ljava/lang/Object;)Ljava/lang/String;") //
                           || clazz.equals("java/lang/StringBuilder") && methodName.startsWith("append");
                  }

                  @Override
                  public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
                     switch (opcode) {
                        case Opcodes.GETSTATIC:
                        case Opcodes.GETFIELD:
                           // pushing a field value onto the stack; nullability unknown
                           operandStack.push(Nullability.UNKNOWN);
                           break;
                        case Opcodes.PUTSTATIC:
                        case Opcodes.PUTFIELD:
                           if (!operandStack.isEmpty()) {
                              operandStack.pop();
                           }
                           break;
                        default:
                           operandStack.push(Nullability.UNKNOWN);
                           break;
                     }
                  }

                  @Override
                  public void visitInsn(final int opcode) {
                     switch (opcode) {
                        case Opcodes.ACONST_NULL:
                           operandStack.push(Nullability.NULLABLE);
                           break;
                        case Opcodes.ARETURN:
                           if (operandStack.isEmpty()) {
                              // stack underflow; treat as possibly null
                              returnNullabilities.add(Nullability.UNKNOWN);
                           } else {
                              final Nullability returnValue = operandStack.pop();
                              returnNullabilities.add(returnValue);
                           }
                           // clear the operand stack after a return
                           operandStack.clear();
                           break;
                        case Opcodes.DUP:
                           if (operandStack.isEmpty()) {
                              // stack underflow; treat as possibly null
                              operandStack.push(Nullability.UNKNOWN);
                           } else {
                              final Nullability top = operandStack.peek();
                              operandStack.push(top);
                           }
                           break;
                        case Opcodes.POP:
                           if (!operandStack.isEmpty()) {
                              operandStack.pop();
                           }
                           break;
                        default:
                           // for other instructions, assume they might alter the stack
                           // for simplicity, we reset the stack to UNKNOWN
                           operandStack.clear();
                           operandStack.push(Nullability.UNKNOWN);
                           break;
                     }
                  }

                  @Override
                  public void visitLdcInsn(final Object constant) {
                     if (constant instanceof Integer || constant instanceof Float //
                           || constant instanceof Long || constant instanceof Double //
                           || constant instanceof String //
                           || constant instanceof Type || constant instanceof Handle) {
                        // primitive constants, string constants, class literals, method handles are non-null
                        operandStack.push(Nullability.NON_NULL);
                     } else if (constant instanceof ConstantDynamic) {
                        // ConstantDynamic may resolve to null, so treat it as possibly null
                        operandStack.push(Nullability.UNKNOWN);
                     } else {
                        // handle other unexpected types conservatively as possibly null
                        operandStack.push(Nullability.UNKNOWN);
                     }
                  }

                  @Override
                  public void visitMethodInsn(final int opcode, final String owner, final String name, final String descriptor,
                        final boolean isInterface) {
                     // pop arguments off the stack, push return value
                     final Type methodType = Type.getMethodType(descriptor);
                     for (int i = 0, argCount = methodType.getArgumentTypes().length; i < argCount; i++) {
                        if (!operandStack.isEmpty()) {
                           operandStack.pop();
                        }
                     }
                     if (opcode != Opcodes.INVOKESTATIC && opcode != Opcodes.INVOKEDYNAMIC) {
                        // pop 'this' reference
                        if (!operandStack.isEmpty()) {
                           operandStack.pop();
                        }
                     }

                     // push the return value onto the stack
                     switch (methodType.getReturnType().getSort()) {
                        case Type.VOID:
                           break;
                        case Type.OBJECT:
                        case Type.ARRAY:
                           if (isKnownNonNullMethod(owner, name, descriptor)) {
                              operandStack.push(Nullability.NON_NULL);
                           } else {
                              // reference type; nullability unknown
                              operandStack.push(Nullability.UNKNOWN);
                           }
                           break;
                        default:
                           // primitive type; definitely non-null
                           operandStack.push(Nullability.NON_NULL);
                     }
                  }

                  @Override
                  public void visitTypeInsn(final int opcode, final String type) {
                     if (opcode == Opcodes.NEW) {
                        operandStack.push(Nullability.NON_NULL);
                     } else {
                        operandStack.push(Nullability.UNKNOWN);
                     }
                  }

                  @Override
                  public void visitVarInsn(final int opcode, final int varIndex) {
                     switch (opcode) {
                        case Opcodes.ALOAD:
                           // loading a reference from a local variable
                           final Nullability varNullability = localVariableNullability.getOrDefault(varIndex, Nullability.UNKNOWN);
                           operandStack.push(varNullability);
                           break;
                        case Opcodes.ASTORE:
                           // storing a value into a local variable
                           if (!operandStack.isEmpty()) { // CHECKSTYLE:IGNORE .*
                              final Nullability valueNullability = operandStack.pop();
                              localVariableNullability.put(varIndex, valueNullability);
                           } else {
                              // stack underflow; assume possibly null
                              localVariableNullability.put(varIndex, Nullability.UNKNOWN);
                           }
                           break;
                        default:
                           operandStack.push(Nullability.UNKNOWN);
                           break;
                     }
                  }
               };
            return super.visitMethod(access, name, descriptor, signature, exceptions);
         }
      }, 0);

      if (returnNullabilities.isEmpty())
         // no return statements found (shouldn't happen); nullability unknown
         return Nullability.UNKNOWN;

      if (returnNullabilities.contains(Nullability.NULLABLE))
         return Nullability.NULLABLE;

      if (returnNullabilities.contains(Nullability.UNKNOWN))
         return Nullability.UNKNOWN;

      return Nullability.NON_NULL;
   }
}
