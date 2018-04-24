/****************************************************************
 * Licensed to the Apache Software Foundation (ASF) under one   *
 * or more contributor license agreements.  See the NOTICE file *
 * distributed with this work for additional information        *
 * regarding copyright ownership.  The ASF licenses this file   *
 * to you under the Apache License, Version 2.0 (the            *
 * "License"); you may not use this file except in compliance   *
 * with the License.  You may obtain a copy of the License at   *
 *                                                              *
 *   http://www.apache.org/licenses/LICENSE-2.0                 *
 *                                                              *
 * Unless required by applicable law or agreed to in writing,   *
 * software distributed under the License is distributed on an  *
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY       *
 * KIND, either express or implied.  See the License for the    *
 * specific language governing permissions and limitations      *
 * under the License.                                           *
 ****************************************************************/

package org.apache.james.protocols.smtp.core.fastfail;

import org.apache.james.core.Domain;
import org.apache.james.core.MailAddress;
import org.apache.james.protocols.smtp.SMTPRetCode;
import org.apache.james.protocols.smtp.SMTPSession;
import org.apache.james.protocols.smtp.dsn.DSNStatus;
import org.apache.james.protocols.smtp.hook.HookResult;
import org.apache.james.protocols.smtp.hook.HookReturnCode;
import org.apache.james.protocols.smtp.hook.RcptHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handler which want to do an recipient check should extend this
 */
public abstract class AbstractValidRcptHandler implements RcptHook {
    private static final Logger LOGGER = LoggerFactory.getLogger(AbstractValidRcptHandler.class);

    @Override
    public HookResult doRcpt(SMTPSession session, MailAddress sender, MailAddress rcpt) {
        if (!isLocalDomain(session, rcpt.getDomain())) {
            return handleRemoteDomain(session, rcpt);
        }
        return handleLocalDomain(session, rcpt);
    }

    public HookResult handleLocalDomain(SMTPSession session, MailAddress rcpt) {
        if (!isValidRecipient(session, rcpt)) {
            return reject(rcpt);
        }
        return HookResult.declined();
    }

    public HookResult handleRemoteDomain(SMTPSession session, MailAddress rcpt) {
        if (!session.isRelayingAllowed()) {
            LOGGER.debug("Unknown domain {} so reject it", rcpt.getDomain());
        }
        return HookResult.declined();
    }

    public HookResult reject(MailAddress rcpt) {
        LOGGER.info("Rejected message. Unknown user: {}", rcpt);
        return new HookResult(HookReturnCode.DENY,
            SMTPRetCode.MAILBOX_PERM_UNAVAILABLE,
            DSNStatus.getStatus(DSNStatus.PERMANENT,DSNStatus.ADDRESS_MAILBOX) + " Unknown user: " + rcpt.toString());
    }

    /**
     * Return true if email for the given recipient should get accepted
     */
    protected abstract boolean isValidRecipient(SMTPSession session, MailAddress recipient);
    
    /**
     * Return true if the domain is local
     */
    protected abstract boolean isLocalDomain(SMTPSession session, Domain domain);
}
