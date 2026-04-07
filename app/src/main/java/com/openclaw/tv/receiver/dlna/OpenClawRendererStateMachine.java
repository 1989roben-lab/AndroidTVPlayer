package com.openclaw.tv.receiver.dlna;

import org.fourthline.cling.support.avtransport.impl.AVTransportStateMachine;
import org.seamless.statemachine.States;

@States({
        OpenClawNoMediaPresent.class,
        OpenClawStopped.class,
        OpenClawPlaying.class
})
public interface OpenClawRendererStateMachine extends AVTransportStateMachine {
}
