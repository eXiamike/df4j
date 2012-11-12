/*
 * Copyright 2011 by Alexei Kaigorodov
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package com.github.rfqu.df4j.core;

import java.util.concurrent.Executor;

/**
 * General dataflow node with several inputs and outputs.
 * Firing occur when all inputs are filled.
 * Typical use case is:
 *  - create 1 or more pins for inputs and/or outputs
 *  - redefine abstract methods removeTokens() and act()
 */
public abstract class BaseActor extends Task {
	static final int allOnes=0xFFFFFFFF;
    private int pinCount=0;
    private int pinMask=0; // mask with 1 for all existing pins
    private int readyPins=0;  // mask with 1 for all ready pins
    protected boolean fired=false; // true when this actor runs
    
    public BaseActor(Executor executor) {
    	super(executor);
    }

    public BaseActor() {
    }

    /**
     * @return true if the actor has all its pins on and so is ready for execution
     */
    protected final boolean isReady() {
		return readyPins==pinMask;
	}

    /**
     * Stores input messages.
     * Can be turned on or off. 
     * Initial state should be off, to prevent premature firing.
     */
    protected abstract class Pin {
    	private final int pinBit; // distinct for all other pins of the node 

    	protected Pin(){
        	synchronized (BaseActor.this) {
            	int count = pinCount;
                if (count==32) {
              	  throw new IllegalStateException("only 32 pins could be created");
                }
                pinBit = 1<<count;
            	pinMask=pinMask|pinBit;
                pinCount++;
			}
        }

    	/**
    	 * sets pin's bit on and fires task if all pins are on
    	 *  @return true if actor became ready and must be fired
    	 */
        protected boolean turnOn() {
            readyPins |= pinBit;
            if (fired || !isReady()) {
                return false;
            }
            fired = true; // to prevent multiple concurrent firings
            return true;
        }

        /**
         * sets pin's bit off
         */
        protected void turnOff() {
            readyPins &= ~pinBit;
        }

        protected boolean isOn() {
            return (readyPins&pinBit)!=0;
        }

        protected boolean isOff() {
            return (readyPins&pinBit)==0;
        }
    }

    /**
     *  Stops/allows actor execution
     */
    protected class Switch extends Pin {
    	
        public Switch() { }

        public void on() {
        	boolean doFire;
            synchronized (BaseActor.this) {
            	if (isOn()) {
    				throw new IllegalStateException("turned on already"); 
            	}
            	doFire=turnOn();
            }
            if (doFire) {
            	fire();
            }
        }

        public void off() {
            synchronized (BaseActor.this) {
                if (isOff()) {
    				throw new IllegalStateException("turned off already"); 
            	}
            	turnOff();
            }
        }
    }

    /** 
     * holds tokens without data 
     */
    protected class Semaphore extends Pin {
        private int count=0;
        
        public void up(int delta) {
            synchronized (BaseActor.this) {
                count+=delta;
                if (count==1) {
                    turnOn();
                }
            }
        }

        public void up() {
            up(1);
        }

        public void remove() {
            synchronized (BaseActor.this) {
                if (count==0) {
                    throw new IllegalStateException("place is empty"); 
                }
                count--;
                if (count==0) {
                    turnOff();
                }
            }
        }

        protected boolean isEmpty() {
            return count==0;
        }
    }

    /**
     * Token storage with standard Port interface.
     * @param <T> type of accepted tokens.
     */
    protected abstract class BasePort<T> extends Pin implements Port<T> {
        public T value=null;

        @Override
        public void send(T token) {
        	boolean doFire;
            synchronized (BaseActor.this) {
            	add(token);
            	doFire=turnOn();
            }
            if (doFire) {
            	fire();
            }
        }

        public T consume() {
            synchronized (BaseActor.this) {
                if (isEmpty() ) {
                    throw new IllegalStateException("no tokens");
                }
                value = remove();
            	if (isEmpty()) {
                	turnOff();
            	}
            	return value;
            }
        }

        /**
         * saves passed token
         * @param newToken
         */
		protected abstract void add(T newToken);
		/**
		 * 
		 * @return true if the pin is not ready
		 */
		protected abstract boolean isEmpty();
		/**
		 * removes token from the storage
		 * @return removed token
		 */
		protected abstract T remove();
    }

    /** A Queue of tokens of type <T>
     * @param <T> 
     */
    public class StreamInput<T extends Link> extends BasePort<T> implements StreamPort<T>{
    	private LinkedQueue<T> queue=new LinkedQueue<T>();
    	private boolean closeRequested=false;
    	private boolean closeHandled=false;

        public StreamInput() { }

		@Override
		protected void add(T token) {
			if (token==null) {
				throw new IllegalArgumentException("operand may not be null"); 
			}
			queue.add(token);
		}

		@Override
        public boolean isEmpty() {
			if (!queue.isEmpty()) {
				return false;
			}
			return closeRequested==closeHandled;
		}

		@Override
		protected T remove() {
			T res = queue.poll();
			if (res!=null) {
				return res;
			}
			if (closeRequested) {
				closeHandled=true;
			}
			return null;
		}

		public boolean isClosed() {
			return closeRequested;
		}

		/** Signals the end of the stream. 
		 * Turns this pin on. Removed value is null 
		 * (null cannot be send with StreamInput.add(message)).
		 */
		@Override
		public void close() {
        	boolean doFire;
            synchronized (BaseActor.this) {
    			closeRequested=true;
            	doFire=turnOn();
            }
            if (doFire) {
            	fire();
            }
		}
    }

    /**
     * 
     * This pin carries demand(s) of the result.
     * Demand is two-fold: it is a pin, so firing possible only if
     * someone demanded the execution, and it holds consumer's port where
     * the result should be sent. 
     * @param <R>  type of result
     */
    public class Demand<R> extends Pin implements DataSource<R>, Callback<R> {
        private Promise<R> listeners=new Promise<R>();

        /** indicates a demand
         * @param sink Port to send the result
         * @return 
         */
        @Override
        public DataSource<R> addListener(Callback<R> sink) {
        	boolean doFire;
            synchronized (BaseActor.this) {
            	listeners.addListener(sink);
            	doFire=turnOn();
            }
            if (doFire) {
            	fire();
            }
            return this;
    	}

    	/** satisfy demand(s)
    	 */
    	@Override
		public void send(R m) {
			listeners.send(m);
		}

        @Override
        public void sendFailure(Throwable exc) {
            listeners.sendFailure(exc);
        }
    }
}