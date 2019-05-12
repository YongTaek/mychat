import * as at from '../constants/ActionTypes';

const defaultState = {
    bodies: [],
    scrollId: undefined
};

export default function (state = defaultState, action) {
    switch (action.type) {
        case at.RECV_TEXT:
            const bodies = [...state.bodies];
            const message = bodies.filter(m => m.messageId === action.payload.id);
            console.debug(message);
            if (message.length === 0) {
                return {
                    bodies: [
                        ...state.bodies, {
                            messageId: action.payload.id,
                            id: action.payload.username,
                            contents: action.payload.contents,
                            unReadUsers: action.payload.unReadUsers
                        }
                    ],
                    scrollId: action.payload.id
                }
            } else {
                const newBodies = bodies.map(m => {
                    if (m.messageId === action.payload.id) {
                        m.unReadUsers = action.payload.unReadUsers
                    }
                })
                return {
                    bodies: newBodies,
                    scrollId: undefined
                }
            }
        case at.CLICK_MENTION:
            return {
                ...state,
                scrollId: action.payload.messageId
            };
        default:
            return state;
    }
};
