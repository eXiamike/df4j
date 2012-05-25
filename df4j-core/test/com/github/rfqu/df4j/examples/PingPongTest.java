/*
 * Copyright 2011 by Alexei Kaigorodov
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.github.rfqu.df4j.examples;

import java.io.PrintStream;
import java.util.Random;
import java.util.concurrent.CountDownLatch;

import org.junit.Before;
import org.junit.Test;

import com.github.rfqu.df4j.core.*;
import com.github.rfqu.df4j.util.Request;


/**
 * A set of identical Ping Actors, passing tokens to a single Pong actor, which
 * returns tokens back to sender. A tokens dies after predefined number of hops.
 * 
 */
public class PingPongTest {
    final static int NUM_ACTORS = 1000; // number of Ping nodes
    final static int NUM_TOKENS = NUM_ACTORS; // number of tokens
    final static int TIME_TO_LIVE = 1000; // hops
    final static int nThreads = Runtime.getRuntime().availableProcessors();
    final static int times = 5;
    PrintStream out = System.out;

    @Before
    public void init() {
        out.println("Network with " + NUM_ACTORS + " nodes, " + NUM_TOKENS + " tokens, with " + TIME_TO_LIVE + " each, on " + nThreads + " threads");
    }

    public static void main(String args[]) throws InterruptedException {
        PingPongTest nt = new PingPongTest();
        nt.init();
        nt.runTest();
    }

    @Test
    public void runTest() throws InterruptedException {
		for (int i = 0; i < times; i++) {
            runPingPong();
        }
	}

    /**
     * the type of messages floating between nodes
     */
    static class Token extends Request<Token> {
        int hops_remained;

        public Token(int hops_remained) {
            this.hops_remained = hops_remained;
        }
    }
    
    /**
     * The pinging actor
     * 
     */
    static class Ping extends Actor<Token> {
        Pong pong;
        Port<Token> sink;
        {start();}

        public Ping(Pong pong, Port<Token> sink) {
            this.pong = pong;
            this.sink = sink;
        }

        /**
         * Handle incoming messages. For each received packet, decrease the
         * number of remaining hops. If number of hops become zero, send it to
         * sink, otherwise send to the Pong actor.
         */
        protected void act(Token token) throws Exception {
            if (token.callback == null) {
                token.callback = this;
                pong.send(token);
            } else {
                int nextVal = token.hops_remained - 1;
                if (nextVal == 0) {
                    sink.send(token);
                } else {
                    token.hops_remained = nextVal;
                    pong.send(token);
                }
            }
        }
    }

    /**
     * The ponging actor
     * 
     */
    static class Pong extends Actor<Token> {
        private final Port<Token> sink;
        {start();}

        public Pong(Port<Token> sink) {
            this.sink = sink;
        }

        /**
         * the method to handle incoming messages for each received packet,
         * decrease the number of remaining hops. If number of hops become zero,
         * send it to sink, otherwise send it back to the Ping actor.
         */
        @Override
        protected void act(Token token) throws Exception {
            int nextVal = token.hops_remained - 1;
            if (nextVal == 0) {
                sink.send(token);
            } else {
                token.hops_remained = nextVal;
                token.reply(token);
            }
        }
    }

    /**
     * The ending node. This is a Port rather than actor, to be accessed outside
     * the actor world.
     */
    static class MessageSink extends CountDownLatch implements Port<Token> {
        int remain;

        public MessageSink(int count) {
            super(count);
            remain = count;
        }

        @Override
        public void send(Token message) {
            super.countDown();
            remain--;
        }

    }

    /**
     * the core of the test
     */
    float runPingPong() throws InterruptedException {
        long startTime = System.currentTimeMillis();

        MessageSink sink = new MessageSink(NUM_TOKENS);
        Ping[] pings = new Ping[NUM_ACTORS];
        Random rand = new Random(1);

        // create Pong actor
        Pong pong = new Pong(sink);
        // create Ping actors
        for (int i = 0; i < pings.length; i++) {
            pings[i] = new Ping(pong, sink);
        }
        // create tokens, send them to randomly chosen actors
        for (int i = 0; i < NUM_TOKENS; i++) {
            pings[rand.nextInt(pings.length)].send(new Token(TIME_TO_LIVE));
        }

        // wait for all packets to die.
        sink.await();

        // report timings
        long etime = (System.currentTimeMillis() - startTime);
        float switchnum = NUM_TOKENS * ((long) TIME_TO_LIVE);
        float delay = etime * 1000 * nThreads / switchnum;
        out.println("Elapsed=" + etime / 1000f + " sec; rate=" + (1 / delay) + " messages/mks/core; mean hop time=" + (delay * 1000) + " ns");
        return delay;
    }

}
