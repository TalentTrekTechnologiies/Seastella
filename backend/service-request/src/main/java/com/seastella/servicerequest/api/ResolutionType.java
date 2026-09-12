package com.seastella.servicerequest.api;

/** How a request was ultimately resolved. */
public enum ResolutionType {
    /** The guided checks fixed it. */
    ASSISTANT,
    /** The live agent conversation fixed it. */
    LIVE_CHAT,
    /** A workaround was applied; see OI-11 on whether a visit still follows. */
    TEMPORARY_FIX,
    /** A Service Engineer attended. */
    ENGINEER_VISIT,
    /** Not yet resolved. */
    NONE
}
