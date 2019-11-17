/*
 *
 *  * ******************************************************************************
 *  * Copyright (C) 2014-2019 Dennis Sheirer
 *  *
 *  * This program is free software: you can redistribute it and/or modify
 *  * it under the terms of the GNU General Public License as published by
 *  * the Free Software Foundation, either version 3 of the License, or
 *  * (at your option) any later version.
 *  *
 *  * This program is distributed in the hope that it will be useful,
 *  * but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  * GNU General Public License for more details.
 *  *
 *  * You should have received a copy of the GNU General Public License
 *  * along with this program.  If not, see <http://www.gnu.org/licenses/>
 *  * *****************************************************************************
 *
 *
 */
package io.github.dsheirer.channel.state;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.squelch.SquelchStateEvent;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * Channel state tracks the overall state of all processing modules and decoders configured for the channel and
 * provides squelch control and decoder state reset events.
 *
 * Uses a state enumeration that defines allowable channel state transitions in order to track a call or data decode
 * event from start to finish.  Uses a timer to monitor for inactivity and to provide a FADE period that indicates
 * to the user that the activity has stopped while continuing to provide details about the call, before the state is
 * reset to IDLE.
 *
 * State Descriptions:
 * IDLE:  Normal state. No voice or data call activity
 * CALL/DATA/ENCRYPTED/CONTROL:  Decoding states.
 * FADE:  The phase after a voice or data call when either an explicit call end has been received, or when no new
 * signalling updates have been received, and the fade timer has expired.  This phase allows for gui updates to
 * signal to the user that the call is ended, while continuing to display the call details for the user
 * TEARDOWN:  Indicates a traffic channel that will be torn down for reuse.
 *
 * Identifiers and Channel Metadata
 *
 * The internal identifier collection maintains all of the channel configuration items and monitors source events to
 * update the channel's frequency as it changes.  These updates are broadcast externally to any identifier collection
 * listeners.  The internal identifier collection does not listen to incoming identifier update notifications in order
 * to prevent a feedback loop.  Channel Metadata is the only listener for externally generated updates.
 *
 * Channel Metadata receives all external identifier notifications and any internal notifications generated by the
 * internal identifier collection via feedback.
 */
public class SingleChannelState extends AbstractChannelState implements IDecoderStateEventListener, ISourceEventListener,
    IdentifierUpdateListener, IStateMachineListener
{
    private final static Logger mLog = LoggerFactory.getLogger(SingleChannelState.class);

    public static final long FADE_TIMEOUT_DELAY = 1200;
    public static final long RESET_TIMEOUT_DELAY = 2000;

    private MutableIdentifierCollection mIdentifierCollection = new MutableIdentifierCollection();
    private IdentifierUpdateNotificationProxy mIdentifierUpdateNotificationProxy = new IdentifierUpdateNotificationProxy();
    private DecoderStateEventReceiver mDecoderStateEventReceiver = new DecoderStateEventReceiver();
    private SourceEventListener mInternalSourceEventListener;
    private ChannelMetadata mChannelMetadata;
    private StateMachine mStateMachine = new StateMachine(0);
    private StateMonitoringSquelchController mSquelchController = new StateMonitoringSquelchController(0);

    public SingleChannelState(Channel channel, AliasModel aliasModel)
    {
        super(channel);
        mChannelMetadata = new ChannelMetadata(aliasModel);
        mIdentifierCollection.setIdentifierUpdateListener(mIdentifierUpdateNotificationProxy);
        createConfigurationIdentifiers(channel);

        mStateMachine.addListener(this);
        mStateMachine.addListener(mSquelchController);
        mStateMachine.setChannelType(mChannel.getChannelType());
        mStateMachine.setIdentifierUpdateListener(mIdentifierCollection);
        mStateMachine.setEndTimeoutBuffer(RESET_TIMEOUT_DELAY);
        if(channel.getChannelType() == ChannelType.STANDARD)
        {
            mStateMachine.setFadeTimeoutBuffer(FADE_TIMEOUT_DELAY);
        }
        else
        {
            mStateMachine.setFadeTimeoutBuffer(DecodeConfiguration.DEFAULT_CALL_TIMEOUT_DELAY_SECONDS * 1000);
        }
    }

    @Override
    public void stateChanged(State state, int timeslot)
    {
        ChannelStateIdentifier stateIdentifier = ChannelStateIdentifier.create(state);
        mIdentifierCollection.update(stateIdentifier);
        mChannelMetadata.receive(new IdentifierUpdateNotification(stateIdentifier, IdentifierUpdateNotification.Operation.ADD, timeslot));

        switch(state)
        {
            case IDLE:
                broadcast(new DecoderStateEvent(this, Event.RESET, State.IDLE));
                break;
            case RESET:
                reset();
                mStateMachine.setState(State.IDLE);
                break;
            case TEARDOWN:
                if(mChannel.isTrafficChannel())
                {
                    broadcast(new ChannelEvent(mChannel, ChannelEvent.Event.REQUEST_DISABLE));
                }
                else
                {
                    mStateMachine.setState(State.RESET);
                }
                break;
        }
    }

    @Override
    protected void checkState()
    {
        mStateMachine.checkState();
    }

    @Override
    public void setIdentifierUpdateListener(Listener<IdentifierUpdateNotification> listener)
    {
        mIdentifierUpdateNotificationProxy.setListener(listener);
    }

    @Override
    public void removeIdentifierUpdateListener()
    {
        mIdentifierUpdateNotificationProxy.removeListener();
    }

    @Override
    public void setSquelchStateListener(Listener<SquelchStateEvent> listener)
    {
        mSquelchController.setSquelchStateListener(listener);
    }

    @Override
    public void removeSquelchStateListener()
    {
        mSquelchController.removeSquelchStateListener();
    }

    /**
     * Creates configuration identifiers for the channel name, system, site and alias list name.
     */
    private void createConfigurationIdentifiers(Channel channel)
    {
        mIdentifierCollection.update(DecoderTypeConfigurationIdentifier.create(channel.getDecodeConfiguration().getDecoderType()));

        if(channel.hasSystem())
        {
            mIdentifierCollection.update(SystemConfigurationIdentifier.create(channel.getSystem()));
        }
        if(channel.hasSite())
        {
            mIdentifierCollection.update(SiteConfigurationIdentifier.create(channel.getSite()));
        }
        if(channel.getName() != null && !channel.getName().isEmpty())
        {
            mIdentifierCollection.update(ChannelNameConfigurationIdentifier.create(channel.getName()));
        }
        if(channel.getAliasListName() != null && !channel.getAliasListName().isEmpty())
        {
            mIdentifierCollection.update(AliasListConfigurationIdentifier.create(channel.getAliasListName()));
        }
        if(channel.getSourceConfiguration().getSourceType() == SourceType.TUNER)
        {
            long frequency = ((SourceConfigTuner)channel.getSourceConfiguration()).getFrequency();
            mIdentifierCollection.update(FrequencyConfigurationIdentifier.create(frequency));
        }
        else if(channel.getSourceConfiguration().getSourceType() == SourceType.TUNER_MULTIPLE_FREQUENCIES)
        {
            List<Long> frequencies = ((SourceConfigTunerMultipleFrequency)channel.getSourceConfiguration()).getFrequencies();

            if(frequencies.size() > 0)
            {
                mIdentifierCollection.update(FrequencyConfigurationIdentifier.create(frequencies.get(0)));
            }
        }
    }

    /**
     * Interface to receive channel identifier updates from this channel state and from any
     * decoder states.
     */
    @Override
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return mChannelMetadata;
    }

    /**
     * Updates the channel state identifier collection using the update notification.  This update will be reflected
     * in the internal channel state and will also be broadcast to any listeners, including the channel metadata for
     * this channel state.
     */
    @Override
    public void updateChannelStateIdentifiers(IdentifierUpdateNotification notification)
    {
        mIdentifierCollection.receive(notification);
        mChannelMetadata.receive(notification);
    }

    /**
     * Channel metadata for this channel.
     */
    public Collection<ChannelMetadata> getChannelMetadata()
    {
        return Collections.singletonList(mChannelMetadata);
    }

    /**
     * Resets this channel state and prepares it for reuse.
     */
    @Override
    public void reset()
    {
        mStateMachine.setState(State.RESET);
        broadcast(new DecoderStateEvent(this, Event.RESET, State.IDLE));
        mIdentifierCollection.remove(IdentifierClass.USER);
        sourceOverflow(false);
    }

    @Override
    public void start()
    {
        mIdentifierCollection.broadcastIdentifiers();

        if(mChannel.getChannelType() == ChannelType.TRAFFIC)
        {
            mStateMachine.setState(State.ACTIVE);
        }
    }

    @Override
    public void stop()
    {
        mSquelchController.setSquelchLock(false);
    }

    public void dispose()
    {
        mDecodeEventListener = null;
        mDecoderStateListener = null;
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        if(mInternalSourceEventListener == null)
        {
            mInternalSourceEventListener = new SourceEventListener();
        }

        return mInternalSourceEventListener;
    }

    /**
     * Broadcasts the source event to a registered external source event listener
     */
    protected void broadcast(SourceEvent sourceEvent)
    {
        if(mExternalSourceEventListener != null)
        {
            mExternalSourceEventListener.receive(sourceEvent);
        }
    }

    /**
     * Broadcasts the call event to the registered listener
     */
    protected void broadcast(IDecodeEvent event)
    {
        if(mDecodeEventListener != null)
        {
            mDecodeEventListener.receive(event);
        }
    }

    /**
     * Broadcasts the channel event to a registered listener
     */
    private void broadcast(ChannelEvent channelEvent)
    {
        if(mChannelEventListener != null)
        {
            mChannelEventListener.receive(channelEvent);
        }
    }

    /**
     * Broadcasts a channel state event to any registered listeners
     */
    protected void broadcast(DecoderStateEvent event)
    {
        if(mDecoderStateListener != null)
        {
            mDecoderStateListener.receive(event);
        }
    }

    @Override
    public Listener<DecoderStateEvent> getDecoderStateListener()
    {
        return mDecoderStateEventReceiver;
    }

    /**
     * Listener to receive source events.
     */
    public class SourceEventListener implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            switch(sourceEvent.getEvent())
            {
                case NOTIFICATION_FREQUENCY_CHANGE:
                    //Rebroadcast source frequency change events for the decoder(s) to process
                    long frequency = sourceEvent.getValue().longValue();
                    broadcast(new DecoderStateEvent(this, Event.SOURCE_FREQUENCY, mStateMachine.getState(), frequency));

                    //Create a new frequency configuration identifier so that downstream consumers receive the change
                    //via channel metadata and audio packet updates - this is a silent add that is sent as a notification
                    //to all identifier collections so that they don't rebroadcast the change and cause a feedback loop
                    mIdentifierUpdateNotificationProxy.receive(new IdentifierUpdateNotification(
                        FrequencyConfigurationIdentifier.create(frequency), IdentifierUpdateNotification.Operation.SILENT_ADD, 0));
                    break;
                case NOTIFICATION_MEASURED_FREQUENCY_ERROR:
                    //Rebroadcast frequency error measurements to external listener if we're currently
                    //in an active (ie sync locked) state.
                    if(mStateMachine.getState().isActiveState())
                    {
                        broadcast(SourceEvent.frequencyErrorMeasurementSyncLocked(sourceEvent.getValue().longValue(),
                            mChannel.getChannelType().name()));
                    }
                    break;
            }
        }
    }

    /**
     * DecoderStateEvent receiver wrapper
     */
    public class DecoderStateEventReceiver implements Listener<DecoderStateEvent>
    {
        @Override
        public void receive(DecoderStateEvent event)
        {
            if(event.getSource() != this)
            {
                switch(event.getEvent())
                {
                    case ALWAYS_UNSQUELCH:
                        mSquelchController.setSquelchLock(true);
                        break;
                    case CHANGE_CALL_TIMEOUT:
                        if(event instanceof ChangeChannelTimeoutEvent)
                        {
                            ChangeChannelTimeoutEvent timeout = (ChangeChannelTimeoutEvent)event;
                            mStateMachine.setFadeTimeoutBuffer(timeout.getCallTimeout());
                        }
                    case CONTINUATION:
                    case DECODE:
                    case START:
                        if(State.CALL_STATES.contains(event.getState()))
                        {
                            mStateMachine.setState(event.getState());
                        }
                        break;
                    case END:
                        if(mChannel.isTrafficChannel())
                        {
                            mStateMachine.setState(State.TEARDOWN);
                        }
                        else
                        {
                            mStateMachine.setState(State.FADE);
                        }
                        break;
                    case RESET:
                        /* Channel State does not respond to reset events */
                        break;
                    default:
                        break;
                }
            }
        }
    }

    //TODO: this can probably be removed.  The only reason it exists is so that we can inject frequency change
    //updates directly to the external broadcaster as a silent add.  Otherwise, we could simply register the external
    //listener directly on the identifier collection.

    /**
     * Proxy between the internal identifier collection and the external update notification listener.  This proxy
     * enables access to internal components to broadcast silent identifier update notifications externally.
     */
    public class IdentifierUpdateNotificationProxy implements Listener<IdentifierUpdateNotification>
    {
        private Listener<IdentifierUpdateNotification> mIdentifierUpdateNotificationListener;

        @Override
        public void receive(IdentifierUpdateNotification identifierUpdateNotification)
        {
            if(mIdentifierUpdateNotificationListener != null)
            {
                mIdentifierUpdateNotificationListener.receive(identifierUpdateNotification);
            }
        }

        public void setListener(Listener<IdentifierUpdateNotification> listener)
        {
            mIdentifierUpdateNotificationListener = listener;
        }

        public void removeListener()
        {
            mIdentifierUpdateNotificationListener = null;
        }
    }
}
