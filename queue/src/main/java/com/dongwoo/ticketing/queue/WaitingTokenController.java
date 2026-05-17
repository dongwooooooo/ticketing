package com.dongwoo.ticketing.queue;

import com.dongwoo.ticketing.support.AuthContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class WaitingTokenController {

    private final WaitingQueue queue;
    private final AuthContext authContext;

    public record IssueResponse(String token, long position) {}

    public record StatusResponse(String token, long position, boolean admitted) {}

    @PostMapping("/waiting/tokens")
    public ResponseEntity<IssueResponse> issue(HttpServletRequest request) {
        String userId = authContext.currentUserId(request);
        String token = queue.enqueue(userId);
        long pos = queue.position(token);
        return ResponseEntity.status(HttpStatus.CREATED).body(new IssueResponse(token, pos));
    }

    @GetMapping("/waiting/tokens/{token}")
    public StatusResponse status(@PathVariable String token) {
        long pos = queue.position(token);
        boolean admitted = queue.isAdmitted(token);
        return new StatusResponse(token, pos, admitted);
    }
}
