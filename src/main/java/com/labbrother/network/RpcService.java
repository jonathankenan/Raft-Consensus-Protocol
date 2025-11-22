package com.labbrother.network;

import com.labbrother.model.Message;

public interface RpcService {

    // 1. Request Vote (Dipakai Candidate saat pemilu)
    Message requestVote(Message request);

    // 2. Append Entries (Dipakai Leader untuk kirim log & heartbeat)
    Message appendEntries(Message request);

    // 3. Execute (Dipakai Client untuk perintah set, get, del, dll)
    Message execute(Message request);

    // 4. Request Log (Dipakai Client untuk melihat history log node)
    Message requestLog(Message request);
}