"""协议测试：重复、乱序、旧轮次、容量、阻塞取消与正常结束。"""
import asyncio
import unittest
from external_text import ExternalTextRound


class ExternalTextTest(unittest.IsolatedAsyncioTestCase):
    """功能：验证协议红线。<pre>消息 -> accept -> queue/拒绝</pre>
    案例：同序号不同文本必须失败。设计要点：<ul><li>不调用 GPU。</li></ul>
    """

    async def test_duplicate_and_conflict(self):
        r = ExternalTextRound('a')
        m = dict(type='append', replyId='a', seq=1, text='Hello.')
        self.assertEqual('accepted', await r.accept(m))
        self.assertEqual('duplicate', await r.accept(m))
        self.assertEqual(1, r.queue.qsize())
        with self.assertRaisesRegex(ValueError, 'conflicting'):
            await r.accept({**m, 'text': 'Wrong.'})

    async def test_order_and_round(self):
        r = ExternalTextRound('a')
        for rid, seq, error in [('b', 1, 'stale'), ('a', 2, 'out_of_order')]:
            with self.assertRaisesRegex(ValueError, error):
                await r.accept(dict(type='append', replyId=rid, seq=seq, text='Hi'))

    async def test_end_and_reject_late_append(self):
        r = ExternalTextRound('a')
        await r.accept(dict(type='append', replyId='a', seq=1, text='Hi'))
        await r.accept(dict(type='end', replyId='a', seq=2))
        self.assertEqual(['Hi'], [piece async for piece in r.pieces()])
        with self.assertRaisesRegex(ValueError, 'already_ended'):
            await r.accept(dict(type='append', replyId='a', seq=3, text='late'))

    async def test_bounded_queue_cancel_unblocks_writer(self):
        r = ExternalTextRound('a')
        for seq in range(1, 17):
            await r.accept(dict(type='append', replyId='a', seq=seq, text='Hi'))
        waiting = asyncio.create_task(r.accept(dict(type='append', replyId='a', seq=17, text='blocked')))
        await asyncio.sleep(.01)
        self.assertFalse(waiting.done())
        r.cancel()
        with self.assertRaisesRegex(ValueError, 'stale'):
            await asyncio.wait_for(waiting, 1)

    async def test_size_and_cancel(self):
        r = ExternalTextRound('a')
        with self.assertRaisesRegex(ValueError, 'invalid_text'):
            await r.accept(dict(type='append', replyId='a', seq=1, text='x' * 2049))
        await r.accept(dict(type='cancel', replyId='a', seq=1))
        with self.assertRaisesRegex(ValueError, 'stale'):
            await r.accept(dict(type='end', replyId='a', seq=2))


if __name__ == '__main__':
    unittest.main()
