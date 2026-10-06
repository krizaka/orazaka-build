package com.orazaka.test.architecture;

import static org.junit.jupiter.api.Assertions.fail;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.Source;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LineNumberNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

/**
 * Content fitness function [LOG-001]: no logging call takes a prompt, a response body or a message
 * text as an argument (ADR-064).
 *
 * <p><b>Why a rule, and why a data-class one.</b> A {@code SENSITIVE} pack owes its data a
 * shortened retention and an append-only audit trail. A log file has neither, so a prompt written
 * to one leaves the platform by a channel with no policy at all — and the engine logged every
 * prompt and every response at {@code INFO}, including for correctly governed runs. That is a
 * data-class bypass, the family of door 1 and wider, not an untidy log line.
 *
 * <p><b>The anchor is the argument, read from the operand stack.</b> ArchUnit says which methods a
 * method calls, not which values reach them, and "a method that logs and also reads a prompt" reads
 * the wrong span — the failure ADR-062's first quantity rule was deleted for. So each method is
 * analysed with ASM's {@link SourceInterpreter}, and a value passed to a logger is content when,
 * following it back through locals and copies:
 *
 * <ul>
 *   <li>its static type is a {@linkplain #CONTENT_TYPES content type} — a Spring AI {@code Prompt}
 *       or message, a chat request or response, a job command and its payload;
 *   <li>it was read through a {@linkplain #CONTENT_ACCESSORS content accessor} — {@code prompt()},
 *       {@code refinedPrompt()}, {@code getText()};
 *   <li>it was derived from text content — a concatenation, a {@code String.valueOf}, a method
 *       taking such text and returning text or an object (a whole content-bearing object passed to
 *       a helper is not followed: {@code actorOf(context)} returns an actor id); or
 *   <li>it is the same value the method hands to a {@linkplain #CONTENT_SINKS content-bearing
 *       constructor} as its content — a {@code @RequestParam String} that becomes a {@code
 *       ChatRequest}'s prompt two lines later is a prompt in both places.
 * </ul>
 *
 * <p>No names are read: a variable called {@code prompt} that holds a model name is not content,
 * and a variable called {@code x} that holds a user's turn is. <b>What it cannot see</b>, stated
 * rather than hidden: text that is never typed as content anywhere in the method that logs it — a
 * raw HTTP body read as a {@code String} and logged before it is parsed, as the Whisper client's
 * transcript was. Intra-procedural by construction, too: a helper that receives a {@code String}
 * and logs it is judged on what it can see, which is a {@code String}.
 */
public final class LoggedContentRules {

  private static final String OWN_CODE = "com.orazaka";

  /** Types whose instances carry what a user wrote or a model answered. */
  static final Set<String> CONTENT_TYPES =
      Set.of(
          "org/springframework/ai/chat/prompt/Prompt",
          "org/springframework/ai/chat/model/ChatResponse",
          "org/springframework/ai/chat/model/Generation",
          "org/springframework/ai/chat/messages/Message",
          "org/springframework/ai/chat/messages/AbstractMessage",
          "org/springframework/ai/chat/messages/UserMessage",
          "org/springframework/ai/chat/messages/AssistantMessage",
          "org/springframework/ai/chat/messages/SystemMessage",
          "com/orazaka/core/domain/model/chat/ChatRequest",
          "com/orazaka/core/domain/model/chat/ChatRequest$ChatMessage",
          "com/orazaka/core/domain/model/chat/ChatResponse",
          "com/orazaka/core/domain/model/chat/InternalChatRequest",
          "com/orazaka/core/domain/model/chat/InternalChatRequest$ChatMessage",
          "com/orazaka/core/domain/model/chat/InternalChatResponse",
          "com/orazaka/core/domain/model/audio/AudioRequest",
          "com/orazaka/core/domain/model/image/ImageRequest",
          "com/orazaka/core/domain/model/PromptContext",
          "com/orazaka/core/application/pipeline/EnginePipelineContext",
          "com/orazaka/jobs/domain/model/JobCommand",
          "com/orazaka/tools/domain/model/poster/AnalyzePosterRequest",
          "com/orazaka/tools/domain/model/search/SearchWebRequest",
          "com/orazaka/tools/domain/model/audio/AnalyzeAudioExtractRequest");

  /** Accessors that read content out of an object, whatever type they return. */
  static final Set<String> CONTENT_ACCESSORS =
      Set.of(
          "com/orazaka/core/domain/model/chat/ChatRequest#prompt",
          "com/orazaka/core/domain/model/chat/ChatRequest$ChatMessage#content",
          "com/orazaka/core/domain/model/chat/ChatResponse#content",
          "com/orazaka/core/domain/model/chat/InternalChatRequest#prompt",
          "com/orazaka/core/domain/model/chat/InternalChatRequest$ChatMessage#content",
          "com/orazaka/core/domain/model/chat/InternalChatResponse#content",
          "com/orazaka/core/domain/model/audio/AudioRequest#prompt",
          "com/orazaka/core/domain/model/image/ImageRequest#prompt",
          "com/orazaka/core/domain/model/PromptContext#rawUserQuery",
          "com/orazaka/core/domain/model/PromptContext#refinedPrompt",
          "com/orazaka/core/application/pipeline/EnginePipelineContext#promptText",
          "com/orazaka/jobs/domain/model/JobCommand#prompt",
          "com/orazaka/jobs/domain/model/JobCommand#requirePrompt",
          "org/springframework/ai/chat/messages/Message#getText",
          "org/springframework/ai/chat/messages/AbstractMessage#getText",
          "org/springframework/ai/chat/messages/AssistantMessage#getText",
          "org/springframework/ai/chat/messages/UserMessage#getText",
          "org/springframework/ai/chat/prompt/Prompt#getContents",
          // Transport and contract records: the same text, one hop earlier or later.
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/IntentionRequest#prompt",
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/IntentionRequest#goal",
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/ImageGenerationRequest#prompt",
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/CodeGenerationRequest#prompt",
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/VideoGenerationRequest#prompt",
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/SpeechRequest#text",
          "com/orazaka/conversationservice/infrastructure/adapter/rest/dto/ChatStreamRequest#prompt",
          "com/orazaka/knowledgeservice/infrastructure/adapter/rest/dto/ContentResponse#content",
          "com/orazaka/knowledgeservice/infrastructure/adapter/rest/dto/RetrieveRequest#query",
          "com/orazaka/knowledgeservice/infrastructure/adapter/rest/dto/SourceSearchRequest#query",
          "com/orazaka/persistence/domain/model/ChatMessageDto#content",
          "com/orazaka/business/api/ChatPayload#prompt",
          "com/orazaka/business/api/ImagePayload#prompt",
          "com/orazaka/business/api/AgentPayload#goal",
          "com/orazaka/tools/domain/model/search/SearchWebRequest#query",
          "com/orazaka/tools/domain/model/poster/AnalyzePosterRequest#prompt");

  /** Where a method builds content, and which of the call's declared parameters is the content. */
  static final Map<String, List<Integer>> CONTENT_SINKS =
      Map.ofEntries(
          Map.entry("com/orazaka/core/domain/model/chat/ChatRequest#<init>", List.of(0)),
          Map.entry("com/orazaka/core/domain/model/chat/ChatRequest#simple", List.of(0)),
          Map.entry(
              "com/orazaka/core/domain/model/chat/ChatRequest$ChatMessage#<init>", List.of(1)),
          Map.entry("com/orazaka/core/domain/model/chat/InternalChatRequest#<init>", List.of(0)),
          Map.entry("com/orazaka/core/domain/model/chat/InternalChatRequest#simple", List.of(0)),
          Map.entry(
              "com/orazaka/core/domain/model/chat/InternalChatRequest$ChatMessage#<init>",
              List.of(1)),
          Map.entry("com/orazaka/core/domain/model/audio/AudioRequest#<init>", List.of(0)),
          Map.entry("com/orazaka/core/domain/model/image/ImageRequest#<init>", List.of(0)),
          Map.entry("com/orazaka/core/domain/model/PromptContext#<init>", List.of(0, 3)),
          Map.entry("com/orazaka/core/domain/model/PromptContext#withRefinedPrompt", List.of(0)),
          Map.entry("org/springframework/ai/chat/messages/UserMessage#<init>", List.of(0)),
          Map.entry("org/springframework/ai/chat/messages/AssistantMessage#<init>", List.of(0)),
          Map.entry("org/springframework/ai/chat/messages/SystemMessage#<init>", List.of(0)),
          Map.entry("org/springframework/ai/chat/messages/UserMessage$Builder#text", List.of(0)));

  /** Types whose operations on text or collections carry content through to their result. */
  private static final Set<String> CARRYING_OWNERS =
      Set.of(
          "java/lang/String",
          "java/lang/Object",
          "java/lang/CharSequence",
          "java/lang/StringBuilder",
          "java/util/Map",
          "java/util/List",
          "java/util/Collection",
          "java/util/Optional");

  private static final Set<String> LOGGER_OWNERS = Set.of("org/slf4j/Logger");
  private static final Set<String> LOGGER_METHODS =
      Set.of("trace", "debug", "info", "warn", "error");

  /** Return types through which content survives a call that consumed it. */
  private static final Set<String> CARRYING_RETURNS =
      Set.of(
          "Ljava/lang/String;",
          "Ljava/lang/CharSequence;",
          "Ljava/lang/Object;",
          "Ljava/util/Map;",
          "Ljava/util/List;",
          "Ljava/util/Optional;");

  private LoggedContentRules() {}

  /** Asserts [LOG-001] over every production class on this module's runtime classpath. */
  public static void assertNoLoggingCallTakesContent() {
    assertNoLoggingCallTakesContent(
        new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(OWN_CODE));
  }

  /**
   * Asserts [LOG-001] over the given classes: no argument of a logging call is, or was derived
   * from, a prompt, a response body or a message text.
   *
   * <p>Its population is the set of logging calls examined; an empty one fails the rule (GOV-006).
   *
   * @param classes the classes whose methods are analysed
   */
  public static void assertNoLoggingCallTakesContent(JavaClasses classes) {
    List<String> calls = new ArrayList<>();
    List<String> violations = new ArrayList<>();
    for (JavaClass type : classes) {
      ClassNode node = read(type);
      if (node == null) {
        continue;
      }
      for (MethodNode method : node.methods) {
        if (method.instructions.size() == 0) {
          continue;
        }
        new MethodScan(node, method).scan(calls, violations);
      }
    }
    GovernanceSubjects.require(
        "LOG-001", "logging calls in production classes (none were found to examine)", calls);
    if (!violations.isEmpty()) {
      fail(
          "[LOG-001] content reaches a log, a channel with no retention and no audit (ADR-064):\n  "
              + String.join("\n  ", violations));
    }
  }

  private static ClassNode read(JavaClass type) {
    Source source = type.getSource().orElse(null);
    if (source == null) {
      return null;
    }
    try (InputStream bytes = source.getUri().toURL().openStream()) {
      ClassNode node = new ClassNode();
      new ClassReader(bytes).accept(node, 0);
      return node;
    } catch (IOException unreadable) {
      throw new UncheckedIOException("cannot read " + type.getName(), unreadable);
    }
  }

  /** One method, its frames, and the values it builds content from. */
  private static final class MethodScan {

    private final ClassNode owner;
    private final MethodNode method;
    private Frame<SourceValue>[] frames;
    private final Set<Object> contentRoots = new HashSet<>();

    MethodScan(ClassNode owner, MethodNode method) {
      this.owner = owner;
      this.method = method;
    }

    void scan(List<String> calls, List<String> violations) {
      try {
        frames = new Analyzer<>(new SourceInterpreter()).analyze(owner.name, method);
      } catch (AnalyzerException unanalysable) {
        throw new IllegalStateException(
            "[LOG-001] cannot analyse " + owner.name + "." + method.name, unanalysable);
      }
      markSinks();
      for (AbstractInsnNode insn : method.instructions) {
        if (!(insn instanceof MethodInsnNode call) || !isLogging(call)) {
          continue;
        }
        String where =
            owner.name.replace('/', '.') + "." + method.name + ":" + lineOf(insn) + " " + call.name;
        calls.add(where);
        Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
        if (frame == null) {
          continue;
        }
        int arguments = Type.getArgumentTypes(call.desc).length;
        int first = frame.getStackSize() - arguments;
        for (int index = 0; index < arguments; index++) {
          for (SourceValue value : candidates(frame.getStack(first + index))) {
            if (isContent(value, new HashSet<>(), false)
                || intersects(roots(value, new HashSet<>()), contentRoots)) {
              violations.add(where + " — argument " + (index + 1) + " carries content");
              break;
            }
          }
        }
      }
    }

    private static boolean isLogging(MethodInsnNode call) {
      return LOGGER_OWNERS.contains(call.owner) && LOGGER_METHODS.contains(call.name);
    }

    /** Remembers the values this method hands to a content-bearing constructor as its content. */
    private void markSinks() {
      for (AbstractInsnNode insn : method.instructions) {
        if (!(insn instanceof MethodInsnNode call)) {
          continue;
        }
        List<Integer> positions = CONTENT_SINKS.get(call.owner + "#" + call.name);
        Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
        if (positions == null || frame == null) {
          continue;
        }
        int first = frame.getStackSize() - Type.getArgumentTypes(call.desc).length;
        for (int position : positions) {
          contentRoots.addAll(roots(frame.getStack(first + position), new HashSet<>()));
        }
      }
    }

    /**
     * Whether a value is content. {@code textOnly} narrows it to text read or built from content —
     * the only kind that survives being passed through a method this scan cannot see into. A whole
     * {@code PromptContext} is content when logged, but {@code actorOf(context)} returns the actor:
     * treating every helper that takes a content-bearing object as returning content would flag the
     * metadata those objects also carry, and a rule that flags actor ids teaches people to ignore
     * it.
     */
    private boolean isContent(SourceValue value, Set<AbstractInsnNode> seen, boolean textOnly) {
      for (AbstractInsnNode insn : value.insns) {
        if (seen.add(insn) && producesContent(insn, seen, textOnly)) {
          return true;
        }
      }
      return false;
    }

    private boolean producesContent(
        AbstractInsnNode insn, Set<AbstractInsnNode> seen, boolean textOnly) {
      Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
      return switch (insn) {
        case MethodInsnNode call -> {
          Type returned = Type.getReturnType(call.desc);
          if (CONTENT_ACCESSORS.contains(call.owner + "#" + call.name)
              || (!textOnly
                  && returned.getSort() == Type.OBJECT
                  && CONTENT_TYPES.contains(returned.getInternalName()))) {
            yield true;
          }
          if (!CARRYING_RETURNS.contains(returned.getDescriptor()) || frame == null) {
            yield false;
          }
          // Content passed IN survives a call that returns text or an object. Content the call is
          // made ON survives only a text or collection operation: message.jobId() reads a job
          // command and returns an id, not the payload.
          int arguments = Type.getArgumentTypes(call.desc).length;
          // A fresh visited set when the question narrows to text, so an instruction already judged
          // as a whole object is judged again as text rather than skipped.
          Set<AbstractInsnNode> textSeen = textOnly ? seen : new HashSet<>();
          boolean fromArguments = anyInputIsContent(frame, arguments, textSeen, true);
          boolean fromReceiver =
              call.getOpcode() != Opcodes.INVOKESTATIC
                  && CARRYING_OWNERS.contains(call.owner)
                  && frame.getStackSize() > arguments
                  && isContent(
                      frame.getStack(frame.getStackSize() - arguments - 1), textSeen, true);
          yield fromArguments || fromReceiver;
        }
        case InvokeDynamicInsnNode concat ->
            frame != null
                && anyInputIsContent(
                    frame, Type.getArgumentTypes(concat.desc).length, seen, textOnly);
        case FieldInsnNode field ->
            !textOnly
                && Type.getType(field.desc).getSort() == Type.OBJECT
                && CONTENT_TYPES.contains(Type.getType(field.desc).getInternalName());
        case TypeInsnNode typed when typed.getOpcode() == Opcodes.NEW ->
            !textOnly && CONTENT_TYPES.contains(typed.desc);
        case TypeInsnNode typed when typed.getOpcode() == Opcodes.CHECKCAST ->
            (!textOnly && CONTENT_TYPES.contains(typed.desc))
                || (frame != null && anyInputIsContent(frame, 1, seen, textOnly));
        case VarInsnNode load when load.getOpcode() == Opcodes.ALOAD ->
            (!textOnly && CONTENT_TYPES.contains(declaredType(load)))
                || (frame != null && isContent(frame.getLocal(load.var), seen, textOnly));
        case VarInsnNode store when store.getOpcode() == Opcodes.ASTORE ->
            frame != null && anyInputIsContent(frame, 1, seen, textOnly);
        default ->
            switch (insn.getOpcode()) {
              case Opcodes.DUP, Opcodes.DUP_X1, Opcodes.DUP_X2 ->
                  frame != null && anyInputIsContent(frame, 1, seen, textOnly);
              case Opcodes.AALOAD -> frame != null && anyInputIsContent(frame, 2, seen, textOnly);
              default -> false;
            };
      };
    }

    private boolean anyInputIsContent(
        Frame<SourceValue> frame, int count, Set<AbstractInsnNode> seen, boolean textOnly) {
      int size = frame.getStackSize();
      for (int index = Math.max(0, size - count); index < size; index++) {
        if (isContent(frame.getStack(index), seen, textOnly)) {
          return true;
        }
      }
      return false;
    }

    /**
     * A logging argument and, when it is a varargs array, every element stored into it: the values
     * a logger formats are the elements, not the array.
     */
    private List<SourceValue> candidates(SourceValue argument) {
      List<SourceValue> values = new ArrayList<>(List.of(argument));
      for (AbstractInsnNode source : argument.insns) {
        if (source.getOpcode() != Opcodes.ANEWARRAY) {
          continue;
        }
        for (AbstractInsnNode insn : method.instructions) {
          Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
          if (insn.getOpcode() != Opcodes.AASTORE || frame == null || frame.getStackSize() < 3) {
            continue;
          }
          SourceValue array = frame.getStack(frame.getStackSize() - 3);
          if (tracesTo(array, source)) {
            values.add(frame.getStack(frame.getStackSize() - 1));
          }
        }
      }
      return values;
    }

    private boolean tracesTo(SourceValue value, AbstractInsnNode origin) {
      Set<AbstractInsnNode> seen = new HashSet<>();
      Deque<AbstractInsnNode> pending = new ArrayDeque<>(value.insns);
      while (!pending.isEmpty()) {
        AbstractInsnNode insn = pending.poll();
        if (insn == origin) {
          return true;
        }
        Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
        if (seen.add(insn) && insn.getOpcode() == Opcodes.DUP && frame != null) {
          pending.addAll(frame.getStack(frame.getStackSize() - 1).insns);
        }
      }
      return false;
    }

    /**
     * Where a value comes from, as far as identity goes: a parameter slot, or the instruction that
     * first produced it — so the same value is recognised at a sink and at a logging call.
     */
    private Set<Object> roots(SourceValue value, Set<AbstractInsnNode> seen) {
      Set<Object> roots = new HashSet<>();
      Deque<AbstractInsnNode> pending = new ArrayDeque<>(value.insns);
      while (!pending.isEmpty()) {
        AbstractInsnNode insn = pending.poll();
        if (!seen.add(insn)) {
          continue;
        }
        Frame<SourceValue> frame = frames[method.instructions.indexOf(insn)];
        if (insn instanceof VarInsnNode load
            && load.getOpcode() == Opcodes.ALOAD
            && frame != null) {
          SourceValue local = frame.getLocal(load.var);
          if (local.insns.isEmpty()) {
            roots.add("parameter:" + load.var);
          } else {
            pending.addAll(local.insns);
          }
        } else if ((insn.getOpcode() == Opcodes.ASTORE
                || insn.getOpcode() == Opcodes.DUP
                || insn.getOpcode() == Opcodes.CHECKCAST)
            && frame != null
            && frame.getStackSize() > 0) {
          pending.addAll(frame.getStack(frame.getStackSize() - 1).insns);
        } else {
          roots.add(insn);
        }
      }
      return roots;
    }

    private static boolean intersects(Set<Object> left, Set<Object> right) {
      for (Object item : left) {
        if (right.contains(item)) {
          return true;
        }
      }
      return false;
    }

    private String declaredType(VarInsnNode load) {
      if (method.localVariables == null) {
        return "";
      }
      int at = method.instructions.indexOf(load);
      for (LocalVariableNode local : method.localVariables) {
        if (local.index == load.var
            && method.instructions.indexOf(local.start) <= at
            && at <= method.instructions.indexOf(local.end)) {
          return Type.getType(local.desc).getSort() == Type.OBJECT
              ? Type.getType(local.desc).getInternalName()
              : "";
        }
      }
      return "";
    }

    private int lineOf(AbstractInsnNode insn) {
      for (AbstractInsnNode cursor = insn; cursor != null; cursor = cursor.getPrevious()) {
        if (cursor instanceof LineNumberNode line) {
          return line.line;
        }
      }
      return -1;
    }
  }
}
