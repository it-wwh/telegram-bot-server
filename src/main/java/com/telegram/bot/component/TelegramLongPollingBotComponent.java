package com.telegram.bot.component;

import com.google.common.collect.ImmutableMap;
import com.telegram.bot.command.*;
import com.telegram.bot.command.annotation.AdminCommand;
import com.telegram.bot.constant.Button;
import com.telegram.bot.service.ButtonMessageService;
import com.telegram.bot.service.GroupMessageService;
import com.telegram.bot.service.ReplyMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.DefaultBotOptions;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.BotApiMethod;
import org.telegram.telegrambots.meta.api.methods.groupadministration.GetChatAdministrators;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.chatmember.ChatMember;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.nonNull;

/**
 * @author honghui 2022/4/13
 */
@Slf4j
@Component
public class TelegramLongPollingBotComponent extends TelegramLongPollingBot {

  public static String COMMAND_PREFIX = "/";

  @Value("${telegrambot.username}")
  private String username;
  @Value("${telegrambot.botToken}")
  private String botToken;

  private final GroupMessageService groupMessageService;
  private final ButtonMessageService buttonMessageService;
  private final ReplyMessageService replyMessageService;
  private final ImmutableMap<String, Command> commandMap;

  protected TelegramLongPollingBotComponent(ObjectProvider<DefaultBotOptions> defaultBotOptions,
                                            GroupMessageService groupMessageService,
                                            ButtonMessageService buttonMessageService,
                                            ReplyMessageService replyMessageService) {
    super(defaultBotOptions.getIfAvailable(DefaultBotOptions::new));
    this.groupMessageService = groupMessageService;
    this.buttonMessageService = buttonMessageService;
    this.replyMessageService = replyMessageService;
    this.commandMap = ImmutableMap.<String, Command>builder()
        .put("/start", new StartCommand())
        .put("/stop", new StopCommand())
        .put("/help", new HelpCommand())
        .put("/delete", new DeleteCommand()).build();
  }

  @Override
  public void onUpdateReceived(Update update) {
    if (update.getMessage() != null) {
      // 1???????????????
      if (update.getMessage().getNewChatMembers() != null && update.getMessage().getNewChatMembers().size() > 0) {
        List<User> users = update.getMessage().getNewChatMembers();
        users.forEach(user -> {
          log.warn("[????????????]->User[{}]", user);
          SendMessage sendMessage = groupMessageService.joinGroupEvent(update, user);
          send(sendMessage);
        });
        return;
      }

      // 2???????????????
      if (update.getMessage().getLeftChatMember() != null) {
        User user = update.getMessage().getLeftChatMember();
        log.warn("[????????????]->User[{}]", user);
        return;
      }

      // 3?????????????????????
      if (update.hasMessage() && update.getMessage().hasText()) {
        log.warn("[????????????]->update[{}]", update);
        SendMessage message = new SendMessage();
        String text = update.getMessage().getText();
        // 3.1?????????
        if (text.startsWith(COMMAND_PREFIX)) {
          log.info("[????????????]->Command[{}]", text);
          send(findCommand(text, getAdmin(update), update.getMessage().getFrom().getUserName()).execute(update));
          return;
        }

        // 3.2?????????????????????
        if (update.getMessage().getReplyToMessage() != null) {
          message.setChatId(String.valueOf(update.getMessage().getChatId()));
          message = replyMessageService.regexAchievements(text, message);
          send(message);
          return;
        }

      }
    }

    // 4?????????????????????
    if (update.hasCallbackQuery() && update.getCallbackQuery().getData() != null) {
      BotApiMethod<?> message;
      Button button = Button.valueOf(update.getCallbackQuery().getData());
      switch (button) {
        case ADD:
          message = buttonMessageService.addButton(update);
          break;
        case CANCEL:
          message = buttonMessageService.cancelButton(update);
          break;
        default:
          message = buttonMessageService.normalButton(update);
      }
      send(message);
    }

  }

  @Override
  public String getBotUsername() {
    return username;
  }

  @Override
  public String getBotToken() {
    return botToken;
  }

  private ArrayList<ChatMember> getAdmin(Update update) {
    GetChatAdministrators getChatAdministrators = new GetChatAdministrators();
    getChatAdministrators.setChatId(update.getMessage().getChatId().toString());
    try {
      return execute(getChatAdministrators);
    } catch (TelegramApiException e) {
      e.printStackTrace();
    }
    return null;
  }

  private void send(BotApiMethod<?> message) {
    try {
      execute(message);
    } catch (TelegramApiException e) {
      e.printStackTrace();
    }
  }

  private Command findCommand(String text, List<ChatMember> adminList, String username) {
    Command command = commandMap.getOrDefault(text, new UnknownCommand());
    if (isAdminCommand(command)) {
      if (adminList != null && adminList.stream().anyMatch(admin -> username.equals(admin.getUser().getUserName()))) {
        return command;
      } else {
        log.info("[?????????]->Admin[{}]", adminList);
        return new UnauthorizedCommand();
      }
    }
    return command;
  }

  private boolean isAdminCommand(Command command) {
    return nonNull(command.getClass().getAnnotation(AdminCommand.class));
  }

}
