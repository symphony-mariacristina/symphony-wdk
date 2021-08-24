package com.symphony.bdk.workflow.engine.executor;


import com.symphony.bdk.core.service.message.MessageService;
import com.symphony.bdk.core.service.message.model.Message;
import com.symphony.bdk.core.service.stream.util.StreamUtil;
import com.symphony.bdk.gen.api.model.V4AttachmentInfo;
import com.symphony.bdk.gen.api.model.V4Message;
import com.symphony.bdk.gen.api.model.V4MessageSent;
import com.symphony.bdk.gen.api.model.V4SymphonyElementsAction;
import com.symphony.bdk.workflow.swadl.v1.activity.SendMessage;

import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Base64;

@Slf4j
public class SendMessageExecutor implements ActivityExecutor<SendMessage> {

  private static final String OUTPUT_MESSAGE_ID_KEY = "msgId";

  @Override
  @SneakyThrows
  public void execute(ActivityExecutorContext<SendMessage> execution) {
    SendMessage activity = execution.getActivity();
    String streamId = resolveStreamId(execution, activity);
    log.debug("Sending message to room {}", streamId);

    if (streamId.endsWith("=")) { // TODO should be done in the BDK: https://perzoinc.atlassian.net/browse/PLAT-11214
      streamId = StreamUtil.toUrlSafeStreamId(streamId);
    }

    Message messageToSend = this.buildMessage(execution);

    V4Message message;
    if (hasAttachments(messageToSend)) {
      message = execution.messages().send(streamId, messageToSend);
    } else {
      // to avoid refactoring all the tests if we only set content
      message = execution.messages().send(streamId, messageToSend.getContent());
    }

    if (message != null) {
      execution.setOutputVariable(OUTPUT_MESSAGE_ID_KEY, message.getMessageId());
    }
  }

  private String resolveStreamId(ActivityExecutorContext<SendMessage> execution, SendMessage activity) {
    if (activity.getTo() != null && activity.getTo().getStreamId() != null) {
      // either set explicitly in the workflow
      return activity.getTo().getStreamId();

      // or retrieved from the current event
    } else if (execution.getEvent() != null
        && execution.getEvent().getSource() instanceof V4MessageSent) {
      V4MessageSent event = (V4MessageSent) execution.getEvent().getSource();
      return event.getMessage().getStream().getStreamId();

    } else if (execution.getEvent() != null
        && execution.getEvent().getSource() instanceof V4SymphonyElementsAction) {
      V4SymphonyElementsAction event = (V4SymphonyElementsAction) execution.getEvent().getSource();
      return event.getStream().getStreamId();

    } else {
      throw new IllegalArgumentException("No stream id set to send a message");
    }
  }

  private Message buildMessage(ActivityExecutorContext<SendMessage> execution) throws IOException {
    Message.MessageBuilder builder = Message.builder().content(execution.getActivity().getContent());

    if (execution.getActivity().getAttachments() != null) {
      for (SendMessage.Attachment attachment : execution.getActivity().getAttachments()) {
        this.handleFileAttachment(builder, attachment, execution);
        this.handleForwardedAttachment(builder, attachment, execution.messages());
      }
    }

    return builder.build();
  }

  private void handleFileAttachment(Message.MessageBuilder messageBuilder, SendMessage.Attachment attachment,
      ActivityExecutorContext<SendMessage> execution) throws IOException {
    if (attachment.getContentPath() != null) {
      InputStream content = this.loadAttachment(attachment.getContentPath(), execution);
      String filename = Path.of(attachment.getContentPath()).getFileName().toString();
      if (content != null) {
        //TODO: check when the content stream is closed
        messageBuilder.addAttachment(content, filename);
      }
    }
  }

  private void handleForwardedAttachment(Message.MessageBuilder messageBuilder, SendMessage.Attachment attachment,
      MessageService messages) {
    if (attachment.getMessageId() != null) {
      V4Message actualMessage = messages.getMessage(attachment.getMessageId());

      if (actualMessage == null) {
        throw new IllegalArgumentException(String.format("Message with id %s not found", attachment.getMessageId()));
      }

      if (attachment.getAttachmentId() != null) {
        // send the provided attachment only
        if (actualMessage.getAttachments() == null) {
          throw new IllegalStateException(
              String.format("No attachment in requested message with id %s", actualMessage.getMessageId()));
        }

        V4AttachmentInfo attachmentInfo = actualMessage.getAttachments().stream()
            .filter(a -> a.getId().equals(attachment.getAttachmentId()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                String.format("No attachment with id %s found in message with id %s", attachment.getAttachmentId(),
                    attachment.getMessageId())));

        downloadAndAddAttachment(messageBuilder, actualMessage, attachmentInfo, messages);
      } else if (actualMessage.getAttachments() != null) {
        // send all message's attachments
        actualMessage.getAttachments()
            .forEach(a -> downloadAndAddAttachment(messageBuilder, actualMessage, a, messages));
      }
    }
  }

  private void downloadAndAddAttachment(Message.MessageBuilder messageBuilder,
      V4Message actualMessage, V4AttachmentInfo a, MessageService messages) {
    String filename = a.getName();
    byte[] attachmentFromMessage = messages
        .getAttachment(actualMessage.getStream().getStreamId(), actualMessage.getMessageId(), a.getId());
    byte[] decodedAttachmentFromMessage = Base64.getDecoder().decode(attachmentFromMessage);

    messageBuilder.addAttachment(new ByteArrayInputStream(decodedAttachmentFromMessage), filename);
  }

  private InputStream loadAttachment(String attachmentPath, ActivityExecutorContext<SendMessage> execution)
      throws IOException {
    if (attachmentPath == null) {
      return null;
    }

    return execution.getResource(attachmentPath);
  }

  private static boolean hasAttachments(Message messageToSend) {
    return messageToSend.getAttachments() != null && !messageToSend.getAttachments().isEmpty();
  }

}
