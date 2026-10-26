package com.tianji.aigc.controller;

import com.tianji.aigc.service.ChatSessionService;
import com.tianji.aigc.vo.MessageVO;
import com.tianji.aigc.vo.SessionHistoryVO;
import com.tianji.aigc.vo.SessionVO;
import com.tianji.common.utils.UserContext;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/session")
@RequiredArgsConstructor
public class SessionController {

    private final ChatSessionService chatSessionService;

    /**
     * 新建会话
     */
    @PostMapping
    public SessionVO createSession(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return this.chatSessionService.createSession(num);
    }

    /**
     * 获取热门会话
     *
     * @return 热门会话列表
     */
    @GetMapping("/hot")
    public List<SessionVO.Example> hotExamples(@RequestParam(value = "n", defaultValue = "3") Integer num) {
        return this.chatSessionService.hotExamples(num);
    }

    /**
     * 查询历史会话，按更新时间倒序，按时间分组返回
     *
     * @return key为时间分组，value为该分组下的会话列表
     */
    @GetMapping("/history")
    public Map<String, List<SessionHistoryVO>> queryHistory() {
        return this.chatSessionService.queryHistory();
    }

    /**
     * 更新历史会话标题（异步执行，接口立即返回）
     */
    @PutMapping("/history")
    public void updateTitle(@RequestParam("sessionId") String sessionId,
                            @RequestParam("title") String title) {
        // UserContext 是 ThreadLocal，异步线程取不到，所以这里先取出用户id再传进去
        this.chatSessionService.updateTitle(sessionId, title, UserContext.getUser());
    }

    /**
     * 删除历史会话
     */
    @DeleteMapping("/history")
    public void deleteSession(@RequestParam("sessionId") String sessionId) {
        this.chatSessionService.deleteSession(sessionId);
    }

    /**
     * 查询单个历史对话详情
     *
     * @return 对话记录列表
     */
    @GetMapping("/{sessionId}")
    public List<MessageVO> queryBySessionId(@PathVariable("sessionId") String sessionId) {
        return this.chatSessionService.queryBySessionId(sessionId);
    }

}
