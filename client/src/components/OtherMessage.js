import React from 'react';
import ContentContainer from "../containers/ContentContainer";
import UnReadCountContainer from "../containers/UnReadCountContainer";

export default ({body}) => (
    <div style={{
        float: 'left',
        marginRight: '2%',
        padding: '0.5%',
        display: 'grid',
        gridTemplateColumns: 'auto auto'
    }}>

        <div style={{
            backgroundColor: 'lightblue',
            display: 'table',
            color: 'white',
            marginRight: '0.2em'
        }}>
            <ContentContainer contents={body.contents}/>
        </div>
        <UnReadCountContainer body={body}/>
    </div>
)
